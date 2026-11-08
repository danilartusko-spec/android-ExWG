package com.nexus.client.routing

import android.content.Context
import android.util.AtomicFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexus.client.EventLog
import com.nexus.client.NexusApplication
import com.nexus.client.core.routing.RoutingProfile
import com.nexus.client.core.routing.RoutingProfileParser
import com.nexus.client.core.routing.RoutingProfileSourceFallback
import com.nexus.client.data.ProfileMeta
import com.nexus.client.data.ProfileStore
import com.nexus.client.data.RoutingSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mobile.Mobile
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import com.nexus.client.tunnel.ConnectionPhase

data class RoutingOperation(val busy: Boolean, val message: String, val downloading: Boolean = false)

class RoutingRepository(context: Context, private val profiles: ProfileStore) {
    private data class CachedBundleProfile(
        val bundlePath: String,
        val modTime: Long,
        val length: Long,
        val parsed: RoutingProfile,
        val prettyJson: String,
    )
    private data class CachedBundleSummary(
        val bundlePath: String,
        val modTime: Long,
        val overrides: String,
        val summaryJson: String,
    )

    private val app = context.applicationContext as NexusApplication
    private val directory = File(context.filesDir, "routing").also(File::mkdirs)
    private val updateLock = Mutex()
    private val bootstrapLock = Any()
    private val memoryCacheLock = Any()
    @Volatile private var cachedBundleProfile: CachedBundleProfile? = null
    @Volatile private var cachedBundleSummary: CachedBundleSummary? = null
    private val retryAfterTunnelScheduled = AtomicBoolean(false)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableOperation = MutableStateFlow<RoutingOperation?>(null)
    val operation = mutableOperation.asStateFlow()

    private fun progress(message: String) {
        mutableOperation.update { current -> if (current?.busy == true) RoutingOperation(true, message, current.downloading) else current }
    }

    /** Ownership belongs to the repository, not a screen's composition. */
    @Synchronized fun startUpdate(
        source: String = profiles.routingSettings().source,
        overrides: String = profiles.routingSettings().overrides,
        download: Boolean = true,
        rebuild: Boolean = false,
    ) {
        if (mutableOperation.value?.busy == true) return
        val targetSource = if (rebuild) DEFAULT_SOURCE else source.trim().ifBlank { DEFAULT_SOURCE }
        val targetOverrides = if (rebuild) "" else overrides.trim()
        val isNetworkDownload = download || rebuild
        mutableOperation.value = RoutingOperation(
            true,
            when {
                rebuild -> "Восстановление правил с нуля…"
                download -> "Сохранение и обновление базы правил…"
                else -> "Применение локальных правил…"
            },
            downloading = isNetworkDownload,
        )
        background.launch {
            val result = runCatching {
                saveSettings(targetSource, targetOverrides)
                if (download || rebuild) refreshOnce(rebuild).getOrThrow()
            }
            val message = result.fold(
                {
                    when {
                        rebuild -> "Правила и источник восстановлены с нуля; ключи VPN сохранены"
                        download -> "Правила обновлены и применены"
                        else -> "Локальные правила сохранены и применены"
                    }
                },
                {
                    EventLog.error("routing.operation", it)
                    "${it.javaClass.name}: ${it.message}"
                }
            )
            mutableOperation.value = RoutingOperation(false, message)
            if (result.isFailure && app.tunnel.state.value.phase != ConnectionPhase.CONNECTED) scheduleRetryAfterTunnel()
        }
    }

    private fun scheduleRetryAfterTunnel() {
        if (!retryAfterTunnelScheduled.compareAndSet(false, true)) return
        background.launch {
            try {
                app.tunnel.state.first { it.phase == ConnectionPhase.CONNECTED && it.lastHandshakeEpochSeconds > 0 }
                EventLog.add("Повтор загрузки routing после подключения VPN")
                val settings = profiles.routingSettings()
                startUpdate(source = settings.source, overrides = settings.overrides, download = true, rebuild = false)
            } finally {
                retryAfterTunnelScheduled.set(false)
            }
        }
    }

    private val client by lazy {
        LegacyHttps.configure(app, OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS))
            .build()
    }

    fun bundlePath(meta: ProfileMeta? = null): String? =
        globalBundlePath()
            ?: (meta ?: profiles.active() ?: profiles.list().firstOrNull())?.let(::legacyBundlePath)
            ?: synchronized(bootstrapLock) {
                globalBundlePath() ?: installBootstrapBundle()
            }

    private fun loadCachedBundleProfile(meta: ProfileMeta? = null): CachedBundleProfile? = runCatching {
        val bundle = bundlePath(meta) ?: return null
        val file = File(bundle, "profile.json")
        val modTime = file.lastModified()
        val length = file.length()
        val current = cachedBundleProfile
        if (current != null && current.bundlePath == bundle && current.modTime == modTime && current.length == length) {
            return current
        }
        synchronized(memoryCacheLock) {
            val recheck = cachedBundleProfile
            if (recheck != null && recheck.bundlePath == bundle && recheck.modTime == modTime && recheck.length == length) {
                return recheck
            }
            val rawText = file.readText()
            val parsed = RoutingProfileParser.parse(rawText)
            val pretty = runCatching { JSONObject(rawText).toString(2) }.getOrDefault(rawText)
            CachedBundleProfile(bundle, modTime, length, parsed, pretty).also { cachedBundleProfile = it }
        }
    }.getOrNull()

    fun cached(meta: ProfileMeta? = null): RoutingProfile? = loadCachedBundleProfile(meta)?.parsed

    fun rawProfileJson(meta: ProfileMeta? = null): String = loadCachedBundleProfile(meta)?.prettyJson.orEmpty()

    fun summary(overrides: String = profiles.routingSettings().overrides): JSONObject {
        val bundle = bundlePath().orEmpty()
        val modTime = if (bundle.isNotEmpty()) File(bundle, "profile.json").lastModified() else 0L
        val current = cachedBundleSummary
        if (current != null && current.bundlePath == bundle && current.modTime == modTime && current.overrides == overrides) {
            return JSONObject(current.summaryJson)
        }
        return synchronized(memoryCacheLock) {
            val recheck = cachedBundleSummary
            if (recheck != null && recheck.bundlePath == bundle && recheck.modTime == modTime && recheck.overrides == overrides) {
                JSONObject(recheck.summaryJson)
            } else {
                val rawSummary = Mobile.bundleSummary(bundle, overrides)
                cachedBundleSummary = CachedBundleSummary(bundle, modTime, overrides, rawSummary)
                JSONObject(rawSummary)
            }
        }
    }

    fun summary(meta: ProfileMeta): JSONObject = summary(meta.overrides)

    fun explainRoute(target: String, overrides: String = profiles.routingSettings().overrides): JSONObject =
        JSONObject(Mobile.explainRoute(bundlePath().orEmpty(), overrides, target))

    suspend fun refreshStale() {
        val settings = profiles.routingSettings()
        if (bundlePath() == null || System.currentTimeMillis() - settings.lastRoutingUpdate >= MAX_AGE_MS) {
            refresh()
        }
    }

    @Synchronized fun startUpdate(meta: ProfileMeta, download: Boolean = true, rebuild: Boolean = false) {
        startUpdate(source = meta.routingUrl, overrides = meta.overrides, download = download, rebuild = rebuild)
    }

    suspend fun saveSettings(meta: ProfileMeta) = saveSettings(meta.routingUrl, meta.overrides)

    suspend fun saveSettings(source: String, overrides: String) = withContext(Dispatchers.IO) { updateLock.withLock {
        val normalizedSource = source.trim().ifBlank { DEFAULT_SOURCE }
        val normalizedOverrides = overrides.trim()
        validateSourceInput(normalizedSource)
        Mobile.validateBundle(bundlePath().orEmpty(), normalizedOverrides)
        val previous = profiles.routingSettings()
        if (app.tunnel.state.value.tunnelActive && normalizedSource != previous.source) {
            error("Источник правил можно менять только при отключённом VPN")
        }
        app.tunnel.applyRules(bundlePath().orEmpty(), normalizedOverrides)
        try {
            profiles.saveRoutingSettings(previous.copy(source = normalizedSource, overrides = normalizedOverrides))
        } catch (error: Throwable) {
            runCatching { app.tunnel.applyRules(bundlePath().orEmpty(), previous.overrides) }
                .onFailure { EventLog.error("routing.settings.rollback", it) }
            throw error
        }
    } }

    private fun validateSourceInput(source: String) {
        require(!source.startsWith("http://", true)) { "Разрешены только HTTPS-ссылки, Happ-ссылки или текст правил" }
        if (source.startsWith("https://", true)) {
            val uri = runCatching { URI(source) }.getOrElse { throw IllegalArgumentException("Неверная HTTPS-ссылка источника правил", it) }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Неверная HTTPS-ссылка источника правил" }
        } else {
            Mobile.normalizeProfile(source)
        }
    }

    suspend fun refresh(meta: ProfileMeta? = null): Result<RoutingProfile> = background.async { refreshOnce() }.await()

    private suspend fun refreshOnce(rebuild: Boolean = false): Result<RoutingProfile> = withContext(Dispatchers.IO) { updateLock.withLock {
      var committed = false
      runCatching {
        val source = if (rebuild) DEFAULT_SOURCE else profiles.routingSettings().source
        EventLog.add("Обновление общего routing-профиля (source=$source, rebuild=$rebuild)")
        progress("Загрузка профиля маршрутизации… Можно покинуть этот экран.")
        val (normalized, parsed) = RoutingProfileSourceFallback.loadProfile(source) { profileSource ->
            val downloaded = load(profileSource, allowHapp = true)
            val validated = try {
                val normalizedJson = Mobile.normalizeProfile(String(downloaded))
                val parsedProfile = RoutingProfileParser.parse(normalizedJson)
                if (parsedProfile.name == "Custom" && profileSource.startsWith("https://", true)) {
                    val host = runCatching { URI(profileSource.trim()).host }.getOrNull().orEmpty()
                    if (host.isNotBlank()) {
                        val withHostName = JSONObject(normalizedJson).put("Name", host).toString()
                        withHostName to parsedProfile.copy(name = host)
                    } else {
                        normalizedJson to parsedProfile
                    }
                } else {
                    normalizedJson to parsedProfile
                }
            } catch (error: Exception) {
                if (error is CancellationException || error is InterruptedException) throw error
                val role = if (profileSource == source) "selected" else "official-fallback"
                EventLog.error("routing.profile.validate source=$role host=${runCatching { URI(profileSource.trim()).host }.getOrNull()}", error)
                throw error
            }
            if (profileSource != source) EventLog.add("routing.profile.fallback source=routing.help official_source=$profileSource")
            validated
        }
        val fields = JSONObject(normalized)
        val candidate = File(directory, "global-${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            fun write(name: String, bytes: ByteArray) { java.io.FileOutputStream(File(candidate, name)).use { it.write(bytes); it.fd.sync() } }
            write("profile.json", normalized.toByteArray())
            listOf("Geositeurl" to "geosite.dat", "Geoipurl" to "geoip.dat").forEach { (key, filename) ->
                fields.optString(key).takeIf { it.isNotBlank() }?.let { fileSource ->
                    val started = android.os.SystemClock.elapsedRealtime()
                    EventLog.add("routing.file.start name=$filename host=${URI(fileSource).host}")
                    progress("Загрузка $filename… Рабочие правила сохранены.")
                    val bytes = load(fileSource, 64 * 1024 * 1024, false)
                    write(filename, bytes)
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    EventLog.add("routing.file.done name=$filename bytes=${bytes.size} sha256=$digest elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}")
                }
            }
            val currentSettings = profiles.routingSettings()
            check(rebuild || currentSettings.source == source) { "Источник правил изменён во время загрузки" }
            val effectiveOverrides = if (rebuild) "" else currentSettings.overrides
            progress("Проверка полного набора правил и DNS…")
            Mobile.validateBundle(candidate.absolutePath, effectiveOverrides)
            EventLog.add("routing.bundle.validated rebuild=$rebuild ${Mobile.bundleSummary(candidate.absolutePath, effectiveOverrides)}")
            withContext(NonCancellable) {
                progress("Сохранение и применение проверенного набора…")
                val pointer = AtomicFile(File(directory, GLOBAL_POINTER))
                val output = pointer.startWrite()
                try { output.write(candidate.name.toByteArray()); pointer.finishWrite(output) } catch (error: Throwable) { pointer.failWrite(output); throw error }
                committed = true
                if (rebuild) profiles.saveRoutingSettings(currentSettings.copy(source = DEFAULT_SOURCE, overrides = ""))
                profiles.updateRoutingState(System.currentTimeMillis(), "Проверенный набор применён: ${parsed.name}")
                app.tunnel.applyRules(candidate.absolutePath, effectiveOverrides)
            }
            directory.listFiles()?.filter { it.isDirectory && it.name.startsWith("global-") }
                ?.filter { it != candidate }?.sortedByDescending { it.lastModified() }?.drop(if (rebuild) 0 else 1)?.forEach { it.deleteRecursively() }
        } finally { if (!committed) candidate.deleteRecursively() }
        EventLog.add("Routing-профиль обновлён: ${parsed.name}")
        parsed
    }.onFailure { error ->
        if (error is CancellationException) throw error
        val message = if (committed) "Правила сохранены; ошибка применения — переподключите VPN" else "Ошибка загрузки; последний исправный кэш сохранён"
        val lastUpdate = profiles.routingSettings().lastRoutingUpdate
        profiles.updateRoutingState(lastUpdate, message)
        EventLog.error("routing.refresh committed=$committed; $message", error)
    } } }

    private fun load(source: String, limit: Int = MAX_PROFILE_BYTES, allowHapp: Boolean = true): ByteArray {
        val trimmed = source.trim()
        require(!trimmed.startsWith("http://", true)) { "Разрешены только HTTPS-ссылки" }
        if (allowHapp && !trimmed.startsWith("https://", true)) {
            return trimmed.toByteArray().also { require(it.size <= limit) }
        }
        val tunnel = app.tunnel.state.value
        if (tunnel.tunnelActive) {
            EventLog.add("routing.download route=protected-direct; Android Network.bindSocket is not used")
            return try {
                app.tunnel.fetchResourceDirect(trimmed, limit.toLong(), allowHapp)
            } catch (directError: Exception) {
                if (directError is CancellationException || directError is InterruptedException) throw directError
                EventLog.error("routing.download route=protected-direct host=${runCatching { URI(trimmed).host }.getOrNull()}", directError)
                if (app.tunnel.state.value.phase != ConnectionPhase.CONNECTED) throw directError
                EventLog.add("routing.download retry route=proxy; source unchanged; TLS validation enabled")
                try { app.tunnel.fetchResource(trimmed, limit.toLong(), allowHapp) }
                catch (proxyError: Exception) {
                    if (proxyError is CancellationException || proxyError is InterruptedException) throw proxyError
                    proxyError.addSuppressed(directError)
                    EventLog.error("routing.download route=proxy host=${runCatching { URI(trimmed).host }.getOrNull()}", proxyError)
                    throw proxyError
                }
            }
        }
        return try {
            loadSystemDefault(trimmed, limit, allowHapp)
        } catch (directError: Exception) {
            if (directError is CancellationException || directError is InterruptedException) throw directError
            EventLog.error("routing.download route=system-default host=${runCatching { URI(trimmed).host }.getOrNull()}", directError)
            throw directError
        }
    }

    private fun loadSystemDefault(trimmed: String, limit: Int, allowHapp: Boolean): ByteArray {
        // With no active TUN, Android's default route is already the physical
        // network. Do not call Network.getSocketFactory(): some Android 16 OEM
        // builds reject its internal bindSocket() with EPERM.
        val downloader = client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty() && addresses.all(::publicAddress)) { "Адрес routing-профиля должен быть публичным" }
            }
        }).build()
        var current = URI(trimmed)
        repeat(5) {
            require(current.scheme.equals("https", true) && !current.host.isNullOrBlank()) { "Разрешены только HTTPS и Happ-ссылки" }
            // Validate literals too: OkHttp does not call Dns for numeric hosts.
            require(current.userInfo == null) { "Credentials in routing URL are not allowed" }
            val request = Request.Builder().url(current.toString()).header("User-Agent", "ExWG-Android/1.0.0").build()
            val canonicalHost = request.url.host
            if (com.nexus.client.core.awg.IpPrefix.parse(canonicalHost) != null) require(publicAddress(InetAddress.getByName(canonicalHost)))
            downloader.newCall(request).execute().use { connection ->
            EventLog.add("routing.http route=system-default host=$canonicalHost protocol=${connection.protocol} status=${connection.code} content_length=${connection.body?.contentLength()}")
                if (connection.code in 300..399) {
                    val location = connection.header("Location") ?: error("Перенаправление без адреса")
                    if (allowHapp && location.startsWith("happ://", true)) return location.toByteArray().also { require(it.size <= limit) }
                    current = current.resolve(location)
                    return@repeat
                }
                require(connection.code == 200) { "Сервер вернул HTTP ${connection.code}" }
                val body = requireNotNull(connection.body)
                require(body.contentLength() <= limit || body.contentLength() < 0) { "Ответ слишком большой" }
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= limit) { "Ответ слишком большой" }
                        output.write(buffer, 0, count)
                    }
                }
                return output.toByteArray()
            }
        }
        error("Слишком много перенаправлений")
    }

    private fun publicAddress(address: InetAddress): Boolean = !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress &&
        !(address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc) &&
        !(address.address.size == 4 && (address.address[0].toInt() and 255) == 100 && (address.address[1].toInt() and 255) in 64..127)

    private fun globalBundlePath(): String? = runCatching {
        val name = AtomicFile(File(directory, GLOBAL_POINTER)).readFully().toString(Charsets.UTF_8)
        require(name.matches(Regex("global-[a-f0-9-]+")))
        File(directory, name).takeIf { File(it, "profile.json").isFile }?.absolutePath
    }.getOrNull()

    /**
     * A first installation must never depend on routing.help being reachable.
     * This verified snapshot is replaced atomically after the first successful
     * online refresh, while a failed refresh leaves it untouched.
     */
    private fun installBootstrapBundle(): String? {
        val candidate = File(directory, "global-${UUID.randomUUID()}")
        return runCatching {
            check(candidate.mkdir()) { "Не удалось создать встроенный routing-кэш" }
            listOf("profile.json", "geosite.dat", "geoip.dat").forEach { name ->
                app.assets.open("routing-bootstrap/$name").use { input ->
                    java.io.FileOutputStream(File(candidate, name)).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            Mobile.validateBundle(candidate.absolutePath, profiles.routingSettings().overrides)
            val pointer = AtomicFile(File(directory, GLOBAL_POINTER))
            val output = pointer.startWrite()
            try {
                output.write(candidate.name.toByteArray())
                pointer.finishWrite(output)
            } catch (error: Throwable) {
                pointer.failWrite(output)
                throw error
            }
            EventLog.add("Установлен встроенный routing-кэш: ${Mobile.bundleSummary(candidate.absolutePath, profiles.routingSettings().overrides)}")
            candidate.absolutePath
        }.onFailure { error ->
            candidate.deleteRecursively()
            EventLog.error("routing.bootstrap", error)
        }.getOrNull()
    }

    private fun legacyBundlePath(meta: ProfileMeta): String? = runCatching {
        val name = AtomicFile(File(directory, "${meta.id}.current")).readFully().toString(Charsets.UTF_8)
        require(name.matches(Regex("${Regex.escape(meta.id)}-[a-f0-9-]+")))
        File(directory, name).takeIf { File(it, "profile.json").isFile }?.absolutePath
    }.getOrNull()

    suspend fun migrateLegacyBundle() = withContext(Dispatchers.IO) { updateLock.withLock {
        val existingGlobal = globalBundlePath()
        if (existingGlobal != null) {
            cleanupStaleRoutingEntries(File(existingGlobal).name)
            return@withLock
        }
        val owner = profiles.active() ?: profiles.list().firstOrNull() ?: return@withLock
        val legacy = legacyBundlePath(owner)?.let(::File) ?: return@withLock
        runCatching {
            val candidate = File(directory, "global-${UUID.randomUUID()}")
            check(legacy.renameTo(candidate)) { "Не удалось атомарно перенести действующие правила" }
            try {
                Mobile.validateBundle(candidate.absolutePath, profiles.routingSettings().overrides)
                val pointer = AtomicFile(File(directory, GLOBAL_POINTER))
                val output = pointer.startWrite()
                try { output.write(candidate.name.toByteArray()); pointer.finishWrite(output) }
                catch (error: Throwable) { pointer.failWrite(output); throw error }
            } catch (error: Throwable) {
                check(candidate.renameTo(legacy)) { "Не удалось вернуть прежний routing-кэш после ошибки: ${error.message}" }
                throw error
            }
            cleanupStaleRoutingEntries(candidate.name)
            EventLog.add("Действующий routing-кэш перенесён в общее хранилище")
        }.onFailure { EventLog.error("routing.cache.migrate", it) }
    } }

    private fun cleanupStaleRoutingEntries(activeGlobalDirName: String) {
        directory.listFiles().orEmpty().forEach { entry ->
            if (entry.name != GLOBAL_POINTER && entry.name != activeGlobalDirName) {
                runCatching { entry.deleteRecursively() }
            }
        }
    }

    companion object {
        const val DEFAULT_SOURCE = "https://routing.help/"
        private const val MAX_PROFILE_BYTES = 2 * 1024 * 1024
        private const val MAX_AGE_MS = 72L * 60 * 60 * 1000
        private const val GLOBAL_POINTER = "global.current"
    }
}

class RoutingUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as NexusApplication
        return if (application.routing.refresh().isFailure) Result.retry() else Result.success()
    }

    companion object { const val WORK_NAME = "nexus-routing-72h" }
}

