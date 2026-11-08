package com.nexus.client.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nexus.client.BuildConfig
import com.nexus.client.EventLog
import com.nexus.client.NexusApplication
import com.nexus.client.core.update.AppUpdateInfo
import com.nexus.client.core.update.AppUpdateParser
import com.nexus.client.routing.LegacyHttps
import com.nexus.client.tunnel.ConnectionPhase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AppUpdatePhase {
    data object Idle : AppUpdatePhase
    data object Checking : AppUpdatePhase
    data object Available : AppUpdatePhase
    data class Downloading(val progressPercent: Int?) : AppUpdatePhase
    data class ReadyToInstall(val apkFile: File) : AppUpdatePhase
    data class Error(val message: String) : AppUpdatePhase
}

data class AppUpdateState(
    val info: AppUpdateInfo? = null,
    val phase: AppUpdatePhase = AppUpdatePhase.Idle,
    val dismissedForSession: Boolean = false,
)

class AppUpdateManager(context: Context) {
    private val app = context.applicationContext as NexusApplication
    private val updatesDir = File(app.cacheDir, "updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    @Volatile private var checkJob: Job? = null
    @Volatile private var downloadJob: Job? = null
    @Volatile private var pendingInstallApk: File? = null

    init {
        runCatching {
            val currentVersion = AppUpdateParser.normalizeVersion(BuildConfig.VERSION_NAME)
            val installedHash = installedApkSha256()
            updatesDir.listFiles().orEmpty().forEach { file ->
                if (!file.isFile) return@forEach
                val matchesInstalled = installedHash != null &&
                    runCatching { computeFileSha256(file) }.getOrNull().equals(installedHash, ignoreCase = true)
                if (file.name.endsWith(".part") || matchesInstalled ||
                    (installedHash == null && currentVersion.isNotEmpty() && file.name.contains(currentVersion, ignoreCase = true))
                ) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private val client: OkHttpClient by lazy {
        LegacyHttps.configure(
            app,
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS),
        ).build()
    }

    @Synchronized
    fun checkForUpdates(
        silent: Boolean = true,
        onComplete: ((AppUpdateInfo?, Throwable?) -> Unit)? = null,
    ) {
        val currentPhase = mutableState.value.phase
        if (currentPhase is AppUpdatePhase.Checking || currentPhase is AppUpdatePhase.Downloading) return
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return

        if (!silent) {
            mutableState.update { it.copy(phase = AppUpdatePhase.Checking, dismissedForSession = false) }
        }
        checkJob = scope.launch {
            try {
                val rawJson = fetchReleaseJson()
                val parsedInfo = AppUpdateParser.parseReleaseJson(
                    rawJson,
                    BuildConfig.VERSION_NAME,
                    Build.SUPPORTED_ABIS.toList(),
                )
                val info = if (!parsedInfo.available && isSameVersionRebuildAvailable(parsedInfo)) {
                    parsedInfo.copy(available = true)
                } else {
                    parsedInfo
                }
                val safeName = if (info.available && info.assetName.isNotBlank()) sanitizeFileName(info.assetName) else null
                cleanupOldApks(safeName)
                if (info.available && safeName != null) {
                    val existingFile = File(updatesDir, safeName)
                    if (existingFile.isFile && verifyExistingApk(existingFile, info.sha256)) {
                        mutableState.value = AppUpdateState(
                            info = info,
                            phase = AppUpdatePhase.ReadyToInstall(existingFile),
                            dismissedForSession = false,
                        )
                    } else {
                        mutableState.value = AppUpdateState(
                            info = info,
                            phase = AppUpdatePhase.Available,
                            dismissedForSession = false,
                        )
                    }
                } else {
                    pendingInstallApk = null
                    mutableState.value = AppUpdateState(
                        info = info,
                        phase = AppUpdatePhase.Idle,
                        dismissedForSession = false,
                    )
                }
                if (onComplete != null) {
                    withContext(Dispatchers.Main) { onComplete(info, null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                EventLog.error("update.check", error)
                if (!silent) {
                    mutableState.update {
                        it.copy(
                            phase = AppUpdatePhase.Error(error.message ?: "Не удалось проверить обновления"),
                            dismissedForSession = false,
                        )
                    }
                } else {
                    mutableState.update { current ->
                        if (current.phase is AppUpdatePhase.Checking) {
                            current.copy(phase = AppUpdatePhase.Idle)
                        } else {
                            current
                        }
                    }
                }
                if (onComplete != null) {
                    withContext(Dispatchers.Main) { onComplete(null, error) }
                }
            }
        }
    }

    @Synchronized
    fun startDownloadAndInstall(activityContext: Context) {
        val current = mutableState.value
        val info = current.info ?: return
        if (!info.available) return
        if (current.phase is AppUpdatePhase.ReadyToInstall && current.phase.apkFile.isFile) {
            promptInstall(activityContext, current.phase.apkFile)
            return
        }
        if (downloadJob?.isActive == true) return

        val activityRef = WeakReference(activityContext as? Activity)
        mutableState.update { it.copy(phase = AppUpdatePhase.Downloading(0), dismissedForSession = false) }
        downloadJob = scope.launch {
            val safeName = sanitizeFileName(info.assetName)
            val partFile = File(updatesDir, "$safeName.part")
            val finalFile = File(updatesDir, safeName)
            try {
                if (!updatesDir.exists()) {
                    check(updatesDir.mkdirs() || updatesDir.isDirectory) { "Не удалось создать каталог обновлений" }
                }
                ensureInsideUpdatesDir(partFile)
                ensureInsideUpdatesDir(finalFile)
                val (firstFourBytes, actualSha256Hex) = downloadApkToPartFile(
                    initialUrl = info.downloadUrl,
                    expectedSizeBytes = info.sizeBytes,
                    partFile = partFile,
                )
                AppUpdateParser.verifyApkHeaderAndSha256(firstFourBytes, actualSha256Hex, info.sha256)
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                check(partFile.renameTo(finalFile)) { "Не удалось сохранить скачанный APK" }
                mutableState.update {
                    it.copy(
                        phase = AppUpdatePhase.ReadyToInstall(finalFile),
                        dismissedForSession = false,
                    )
                }
                withContext(Dispatchers.Main) {
                    val liveActivity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    promptInstall(liveActivity ?: app, finalFile)
                }
            } catch (cancelled: CancellationException) {
                runCatching { partFile.delete() }
                mutableState.update { state ->
                    state.copy(
                        phase = if (state.info?.available == true) AppUpdatePhase.Available else AppUpdatePhase.Idle,
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                runCatching { partFile.delete() }
                EventLog.error("update.download", error)
                mutableState.update {
                    it.copy(
                        phase = AppUpdatePhase.Error(error.message ?: "Ошибка скачивания обновления"),
                        dismissedForSession = false,
                    )
                }
            }
        }
    }

    @Synchronized
    fun cancelOrDismiss() {
        pendingInstallApk = null
        val activeDownload = downloadJob
        if (activeDownload?.isActive == true) {
            activeDownload.cancel()
            downloadJob = null
            mutableState.update {
                it.copy(phase = if (it.info?.available == true) AppUpdatePhase.Available else AppUpdatePhase.Idle)
            }
        } else {
            mutableState.update { it.copy(dismissedForSession = true) }
        }
    }

    @Synchronized
    fun onHostResume(activity: Activity) {
        val pending = pendingInstallApk ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            if (pending.isFile) {
                promptInstall(activity, pending)
            }
        }
    }

    fun promptInstall(context: Context, apkFile: File) {
        if (!apkFile.isFile) {
            pendingInstallApk = null
            mutableState.update {
                it.copy(phase = if (it.info?.available == true) AppUpdatePhase.Available else AppUpdatePhase.Idle)
            }
            return
        }
        val activeActivity = (context as? Activity)?.takeIf { !it.isFinishing && !it.isDestroyed }
        val launchContext: Context = activeActivity ?: app

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apkFile
            runCatching {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${app.packageName}"),
                ).apply {
                    if (launchContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchContext.startActivity(settingsIntent)
            }.recoverCatching {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    if (launchContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchContext.startActivity(fallbackIntent)
            }.onFailure { error ->
                EventLog.error("update.install.permission", error)
            }
            Toast.makeText(
                app,
                "Разрешите установку обновлений для Ex WG — установка продолжится автоматически",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        pendingInstallApk = null
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (launchContext !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            launchContext.startActivity(intent)
        }.onFailure { error ->
            EventLog.error("update.install", error)
            mutableState.update {
                it.copy(phase = AppUpdatePhase.Error(error.message ?: "Не удалось запустить установку APK"))
            }
        }
    }

    private fun fetchReleaseJson(): String {
        return try {
            fetchReleaseJsonViaOkHttp(AppUpdateParser.DEFAULT_RELEASE_API)
        } catch (ioError: IOException) {
            if (!app.tunnel.state.value.tunnelActive) throw ioError
            try {
                String(
                    app.tunnel.fetchResourceDirect(
                        AppUpdateParser.DEFAULT_RELEASE_API,
                        MAX_RELEASE_JSON_BYTES.toLong(),
                        false,
                    ),
                    Charsets.UTF_8,
                )
            } catch (directError: Exception) {
                if (directError is CancellationException || directError is InterruptedException) throw directError
                if (app.tunnel.state.value.phase == ConnectionPhase.CONNECTED) {
                    String(
                        app.tunnel.fetchResource(
                            AppUpdateParser.DEFAULT_RELEASE_API,
                            MAX_RELEASE_JSON_BYTES.toLong(),
                            false,
                        ),
                        Charsets.UTF_8,
                    )
                } else {
                    directError.addSuppressed(ioError)
                    throw directError
                }
            }
        }
    }

    private fun fetchReleaseJsonViaOkHttp(initialUrl: String): String {
        val metadataClient = client.newBuilder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECTS) {
            val uri = URI(currentUrl)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Некорректный HTTPS-адрес проверки обновлений"
            }
            if (hop > 0) {
                require(
                    AppUpdateParser.isTrustedDownloadUrl(currentUrl) ||
                        uri.host.equals("api.github.com", ignoreCase = true),
                ) {
                    "Отклонено перенаправление при проверке обновлений"
                }
            }
            val request = Request.Builder()
                .url(currentUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent())
                .build()
            val nextRedirectUrl = metadataClient.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    require(hop < MAX_REDIRECTS) { "Слишком много перенаправлений при проверке обновлений" }
                    val location = response.header("Location")?.trim().orEmpty()
                    require(location.isNotEmpty()) { "Перенаправление без заголовка Location" }
                    val resolved = uri.resolve(location).toString()
                    val resolvedUri = URI(resolved)
                    require(
                        AppUpdateParser.isTrustedDownloadUrl(resolved) ||
                            (resolvedUri.scheme.equals("https", ignoreCase = true) &&
                                resolvedUri.host.equals("api.github.com", ignoreCase = true) &&
                                resolvedUri.userInfo == null),
                    ) {
                        "Отклонено перенаправление на недоверенный адрес"
                    }
                    resolved
                } else {
                    if (response.code != 200) {
                        throw IOException("GitHub API вернул HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Пустой ответ GitHub API")
                    val contentLength = body.contentLength()
                    require(contentLength <= MAX_RELEASE_JSON_BYTES) { "Ответ GitHub API слишком большой" }
                    val output = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_RELEASE_JSON_BYTES) {
                                "Ответ GitHub API слишком большой"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    return output.toByteArray().toString(Charsets.UTF_8)
                }
            }
            currentUrl = nextRedirectUrl
        }
        throw IOException("Слишком много перенаправлений при проверке обновлений")
    }

    private suspend fun downloadApkToPartFile(
        initialUrl: String,
        expectedSizeBytes: Long,
        partFile: File,
    ): Pair<ByteArray, String> {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            require(AppUpdateParser.isTrustedDownloadUrl(currentUrl)) {
                "Недоверенный адрес скачивания APK"
            }
            val request = Request.Builder()
                .url(currentUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", userAgent())
                .build()
            val nextRedirectUrl = client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    require(hop < MAX_REDIRECTS) { "Слишком много перенаправлений при скачивании APK" }
                    val location = response.header("Location")?.trim().orEmpty()
                    require(location.isNotEmpty()) { "Перенаправление без заголовка Location" }
                    val nextUrl = URI(currentUrl).resolve(location).toString()
                    require(AppUpdateParser.isTrustedDownloadUrl(nextUrl)) {
                        "Отклонено перенаправление на недоверенный хост"
                    }
                    nextUrl
                } else {
                    if (response.code != 200) {
                        throw IOException("Сервер обновлений вернул HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Пустой ответ сервера обновлений")
                    val contentLength = body.contentLength()
                    require(contentLength <= AppUpdateParser.MAX_APK_BYTES) {
                        "Размер APK превышает допустимый лимит"
                    }
                    val expectedTotal = if (contentLength > 0L) contentLength else expectedSizeBytes
                    var lastPercent: Int? = 0
                    if (expectedTotal <= 0L) {
                        lastPercent = null
                        mutableState.update { state ->
                            if (state.phase is AppUpdatePhase.Downloading) {
                                state.copy(phase = AppUpdatePhase.Downloading(null))
                            } else {
                                state
                            }
                        }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val firstFourBytes = ByteArray(4)
                    var headerCount = 0
                    var totalBytes = 0L
                    FileOutputStream(partFile).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                require(totalBytes <= AppUpdateParser.MAX_APK_BYTES) {
                                    "Размер APK превышает допустимый лимит"
                                }
                                if (headerCount < 4) {
                                    val copyCount = minOf(4 - headerCount, count)
                                    System.arraycopy(buffer, 0, firstFourBytes, headerCount, copyCount)
                                    headerCount += copyCount
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                val nextPercent = if (expectedTotal > 0L) {
                                    ((totalBytes * 100L) / expectedTotal).toInt().coerceIn(0, 100)
                                } else {
                                    null
                                }
                                if (nextPercent != lastPercent) {
                                    lastPercent = nextPercent
                                    mutableState.update { state ->
                                        if (state.phase is AppUpdatePhase.Downloading) {
                                            state.copy(phase = AppUpdatePhase.Downloading(nextPercent))
                                        } else {
                                            state
                                        }
                                    }
                                }
                            }
                        }
                        output.fd.sync()
                    }
                    require(contentLength <= 0L || totalBytes == contentLength) {
                        "Скачивание APK прервано ($totalBytes из $contentLength байт)"
                    }
                    require(headerCount == 4) { "Скачанный файл повреждён или пуст" }
                    val actualSha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
                    return firstFourBytes to actualSha256Hex
                }
            }
            currentUrl = nextRedirectUrl
        }
        throw IOException("Слишком много перенаправлений при скачивании APK")
    }

    private fun isSameVersionRebuildAvailable(info: AppUpdateInfo): Boolean {
        if (info.downloadUrl.isBlank() || info.sha256.isBlank()) return false
        if (AppUpdateParser.compareVersions(info.currentVersion, info.latestVersion) != 0) return false
        val installedHash = installedApkSha256() ?: return false
        return !installedHash.equals(info.sha256.trim(), ignoreCase = true)
    }

    private fun installedApkSha256(): String? {
        return runCatching {
            val sourcePath = app.applicationInfo?.sourceDir ?: return null
            val apkFile = File(sourcePath)
            if (!apkFile.isFile || apkFile.length() <= 4L) return null
            computeFileSha256(apkFile)
        }.getOrNull()
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyExistingApk(file: File, expectedSha256: String?): Boolean {
        val expected = expectedSha256?.takeIf { it.isNotBlank() } ?: return false
        if (!file.isFile || file.length() <= 4L || file.length() > AppUpdateParser.MAX_APK_BYTES) {
            return false
        }
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val firstFourBytes = ByteArray(4)
            var headerCount = 0
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (headerCount < 4) {
                        val copyCount = minOf(4 - headerCount, count)
                        System.arraycopy(buffer, 0, firstFourBytes, headerCount, copyCount)
                        headerCount += copyCount
                    }
                    digest.update(buffer, 0, count)
                }
            }
            if (headerCount < 4) return false
            val actualSha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
            AppUpdateParser.verifyApkHeaderAndSha256(firstFourBytes, actualSha256Hex, expected)
            true
        }.onFailure {
            runCatching { file.delete() }
        }.getOrDefault(false)
    }

    private fun cleanupOldApks(keepSafeAssetName: String?) {
        val files = updatesDir.listFiles() ?: return
        val downloadingActive = downloadJob?.isActive == true
        for (file in files) {
            if (!file.isFile) continue
            val isPart = file.name.endsWith(".part")
            if (isPart && !downloadingActive) {
                runCatching { file.delete() }
            } else if (!isPart && file.name != keepSafeAssetName) {
                runCatching { file.delete() }
            }
        }
    }

    private fun sanitizeFileName(rawName: String): String {
        val cleaned = rawName.trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace("..", "_")
            .trimStart('.')
            .ifBlank { "update.apk" }
        return if (cleaned.endsWith(".apk", ignoreCase = true)) {
            cleaned.dropLast(4) + ".apk"
        } else {
            "$cleaned.apk"
        }
    }

    private fun ensureInsideUpdatesDir(target: File) {
        val baseCanonical = updatesDir.canonicalFile
        val targetCanonical = target.canonicalFile
        require(targetCanonical.parentFile == baseCanonical) {
            "Недопустимое расположение файла обновления"
        }
    }

    private fun userAgent(): String = "ExWG-Android/${BuildConfig.VERSION_NAME}"

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_RELEASE_JSON_BYTES = 2 * 1024 * 1024
    }
}
