package com.nexus.client.tunnel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Build
import android.os.SystemClock
import com.nexus.client.EventLog
import com.nexus.client.NexusApplication
import com.nexus.client.core.awg.AwgConfigParser
import com.nexus.client.core.awg.HandshakeHealth
import com.nexus.client.core.awg.RuntimeHealthMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.config.Config
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, WAITING, DISCONNECTING, ERROR }
data class TunnelSnapshot(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val rxBytes: Long = 0, val txBytes: Long = 0, val lastHandshakeEpochSeconds: Long = 0,
    val rxBytesPerSecond: Long = 0, val txBytesPerSecond: Long = 0,
    val backendVersion: String = "—", val error: String? = null,
    val dnsQueries: Long = 0, val dnsErrors: Long = 0,
    val proxyDnsFailures: Long = 0,
    val dnsCacheHits: Long = 0,
    val directFlows: Long = 0, val proxyFlows: Long = 0, val blockedFlows: Long = 0,
    val profileId: String? = null,
    val tunnelActive: Boolean = false,
    val handshakeAgeSeconds: Long? = null,
)

class TunnelManager(context: Context) {
    private data class PreparedConfig(val parsed: com.nexus.client.core.awg.AwgConfig, val config: Config, val userspace: String)
    private val app = context.applicationContext as NexusApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(TunnelSnapshot())
    val state = mutableState.asStateFlow()
    @Volatile private var service: NexusVpnService? = null
    private var binding: ServiceConnection? = null
    private var pollJob: Job? = null
    private val physicalNetworkEpoch = AtomicLong()
    private val physicalNetworkAvailable = AtomicBoolean(true)
    fun hasPermission() = VpnService.prepare(app) == null
    suspend fun probeDomain(domain: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(service?.probeDomain(domain) ?: "{\"ok\":false}") }
    suspend fun probeDNS(): JSONObject = withContext(Dispatchers.IO) { JSONObject(service?.probeDNS() ?: "{}") }
    suspend fun checkRoute(target: String): JSONObject = withContext(Dispatchers.IO) {
        val activeService = service
        if (state.value.tunnelActive && activeService != null) {
            JSONObject(activeService.checkRoute(target))
        } else {
            app.routing.explainRoute(target)
        }
    }
    fun fetchResource(source: String, limit: Long, allowHapp: Boolean): ByteArray {
        check(state.value.tunnelActive && state.value.phase == ConnectionPhase.CONNECTED) { "Для резервной загрузки нужен подтверждённый VPN" }
        return service?.fetchResource(source, limit, allowHapp) ?: error("VPN-service unavailable")
    }
    fun fetchResourceDirect(source: String, limit: Long, allowHapp: Boolean): ByteArray {
        check(state.value.tunnelActive) { "Защищённая загрузка доступна только при активном VPN" }
        return service?.fetchResourceDirect(source, limit, allowHapp) ?: error("VPN-service unavailable")
    }

    suspend fun connect(rawConfig: String): Result<Unit> = withContext(Dispatchers.IO) { lock.withLock {
        runCatching {
            check(service == null) { "VPN уже запущен" }
            val prepared = prepareConfig(rawConfig)
            require(hasPermission()) { "VPN-разрешение не выдано" }
            val meta = requireNotNull(app.profiles.active()) { "Профиль не выбран" }
            mutableState.value = TunnelSnapshot(phase = ConnectionPhase.CONNECTING, profileId = meta.id)
            EventLog.add("Начало подключения, AWG ${prepared.parsed.generation.label}")
            val directory = app.routing.bundlePath(meta).orEmpty()
            mobile.Mobile.validateBundle(directory, meta.overrides)
            service = bind()
            service!!.start(prepared.config, prepared.userspace, directory, meta.overrides)
            // Publishing VPN routes/DNS is asynchronous in ConnectivityService.
            // Do not announce readiness while app DNS still uses the old network.
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            withTimeout(15_000) {
                while (!vpnPublished(connectivity)) delay(25)
            }
            mutableState.value = TunnelSnapshot(phase = ConnectionPhase.CONNECTING, profileId = meta.id, tunnelActive = true)
            startPolling()
        }.onFailure {
            cleanup()
            val message = "${it.javaClass.name}: ${it.message}"
            mutableState.update { old -> old.copy(phase = ConnectionPhase.ERROR, error = message, tunnelActive = false) }
            EventLog.error("connect", it)
        }.map { Unit }
    } }

    suspend fun switchProfile(profileId: String): Result<Unit> = withContext(Dispatchers.IO) { lock.withLock {
        val previousId = state.value.profileId ?: app.profiles.activeId()
        runCatching {
            check(state.value.tunnelActive && service != null) { "VPN не запущен" }
            if (profileId == previousId) return@runCatching
            val target = requireNotNull(app.profiles.list().firstOrNull { it.id == profileId }) { "Профиль не найден" }
            val prepared = prepareConfig(app.profiles.readConfig(profileId))
            val directory = app.routing.bundlePath(target).orEmpty()
            mobile.Mobile.validateBundle(directory, target.overrides)
            mutableState.update { it.copy(
                phase = ConnectionPhase.CONNECTING,
                profileId = profileId,
                error = null,
                rxBytes = 0,
                txBytes = 0,
                rxBytesPerSecond = 0,
                txBytesPerSecond = 0,
                lastHandshakeEpochSeconds = 0,
                handshakeAgeSeconds = null,
            ) }
            service!!.updateNotification(ConnectionPhase.CONNECTING)
            EventLog.add("profile.hotSwitch begin from=$previousId to=$profileId AWG=${prepared.parsed.generation.label}")
            app.profiles.select(profileId)
            service!!.switchProfile(prepared.config, prepared.userspace, directory, target.overrides)
            startPolling()
        }.onFailure { error ->
            if (previousId != null) app.profiles.select(previousId)
            val running = runCatching { JSONObject(service?.statistics() ?: "{}").optBoolean("running") }.getOrDefault(false)
            if (running && previousId != null) {
                mutableState.value = TunnelSnapshot(phase = ConnectionPhase.CONNECTING, profileId = previousId, tunnelActive = true,
                    error = "Новый профиль не запустился; предыдущий восстановлен: ${error.javaClass.name}: ${error.message}")
                startPolling()
            } else {
                cleanup()
                mutableState.value = TunnelSnapshot(phase = ConnectionPhase.ERROR,
                    error = "${error.javaClass.name}: ${error.message}")
            }
            EventLog.error("profile.hotSwitch", error)
        }.map { Unit }
    } }

    private fun prepareConfig(rawConfig: String): PreparedConfig {
        val checked = AwgConfigParser.parse(rawConfig)
        val config = Config.parse(checked.raw.byteInputStream())
        // Nexus rules select egress; the single AWG peer accepts both IP families selected for Proxy.
        val userspace = config.toAwgUserspaceString().lineSequence()
            .filterNot { it.startsWith("allowed_ip=") || it.isBlank() }
            .joinToString("\n") + "\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\n"
        return PreparedConfig(checked, config, userspace)
    }

    private suspend fun bind(): NexusVpnService {
        val ready = CompletableDeferred<NexusVpnService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete((binder as NexusVpnService.LocalBinder).service) }
            override fun onServiceDisconnected(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("VpnService остановлен"))
                if (binding === this) systemStopped("VPN-служба остановлена", service)
            }
        }
        val intent = Intent(app, NexusVpnService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        check(app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Не удалось запустить VpnService" }
        binding = connection
        return withTimeout(10_000) { ready.await() }
    }

    private fun vpnPublished(connectivity: ConnectivityManager): Boolean =
        if (Build.VERSION.SDK_INT >= 23) {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } else {
            // activeNetwork was added in API 23. Lollipop exposes the VPN in
            // allNetworks after ConnectivityService installs its routes/DNS.
            connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }

    suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) { lock.withLock {
        runCatching {
            mutableState.update { it.copy(phase = ConnectionPhase.DISCONNECTING) }
            service?.updateNotification(ConnectionPhase.DISCONNECTING)
            cleanup()
            mutableState.value = TunnelSnapshot()
            EventLog.add("VPN отключён")
        }
    } }
    private fun cleanup() {
        pollJob?.cancel(); pollJob = null
        val stopping = service; service = null
        runCatching { stopping?.stopTunnel() }.onFailure { EventLog.error("tunnel.close", it) }
        val unbinding = binding; binding = null
        unbinding?.let { runCatching { app.unbindService(it) }.onFailure { error -> EventLog.error("service.unbind", error) } }
        runCatching { app.stopService(Intent(app, NexusVpnService::class.java)) }.onFailure { EventLog.error("service.stop", it) }
    }
    private fun stopLostTunnel(source: NexusVpnService, cause: Throwable) {
        scope.launch { lock.withLock {
            if (service !== source) return@withLock
            EventLog.error("tunnel.runtime.failure; VPN disabled and ordinary network restored", cause)
            mutableState.update { it.copy(phase = ConnectionPhase.DISCONNECTING, error = cause.message) }
            cleanup()
            mutableState.value = TunnelSnapshot(
                phase = ConnectionPhase.ERROR,
                error = "Соединение потеряно; VPN отключён: ${cause.javaClass.name}: ${cause.message}",
            )
        } }
    }
    fun systemStopped(reason: String, source: NexusVpnService?) { scope.launch { lock.withLock {
        if (service !== source) return@withLock // stale callback from a previous connection
        cleanup(); mutableState.value = TunnelSnapshot(error = reason); EventLog.add(reason)
    } } }
    fun serviceDestroyed(source: NexusVpnService) = systemStopped("VpnService завершён", source)

    fun physicalNetworkChanged(source: NexusVpnService, available: Boolean) {
        if (service !== source || !state.value.tunnelActive) return
        physicalNetworkAvailable.set(available)
        physicalNetworkEpoch.incrementAndGet()
        mutableState.update { current -> current.copy(
            phase = if (available) ConnectionPhase.CONNECTING else ConnectionPhase.WAITING,
            error = if (available) null else "Физическая сеть временно недоступна",
            handshakeAgeSeconds = null,
        ) }
        source.updateNotification(if (available) ConnectionPhase.CONNECTING else ConnectionPhase.WAITING)
    }

    suspend fun applyRules(directory: String, overrides: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (state.value.tunnelActive) {
            service?.updateRules(directory, overrides)
            EventLog.add("Новые правила применены без отключения VPN; текущие соединения переподключаются")
        }
    } }

    private fun startPolling() {
        pollJob?.cancel()
        physicalNetworkAvailable.set(service?.hasPhysicalNetwork() == true)
        pollJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var fastPolling = true
            var health = HandshakeHealth(startedAt)
            var observedNetworkEpoch = physicalNetworkEpoch.get()
            var previousHandshake: Pair<Long, Long>? = null
            var previousTraffic: Triple<Long, Long, Long>? = null
            var runtimeHealth = RuntimeHealthMonitor(startedAt)
            var failureScheduled = false
            var previousDnsWarning = false
            while (isActive) {
                // Same lock as disconnect: an old poll can never update a new session.
                lock.withLock {
                    ensureActive()
                    runCatching {
                        val current = service ?: return@withLock
                        current.drainLogs()
                        val stats = JSONObject(current.statistics())
                        check(stats.optBoolean("running")) { "AWG/TUN running=false; $stats" }
                        check(!stats.has("statisticsError")) { stats.getString("statisticsError") }
                        val now = SystemClock.elapsedRealtime()
                        val rx = stats.optLong("rx")
                        val tx = stats.optLong("tx")
                        val traffic = previousTraffic
                        val elapsed = traffic?.let { now - it.first } ?: 0L
                        val rxRate = if (traffic != null && elapsed >= 250L && rx >= traffic.second) (rx - traffic.second) * 1000L / elapsed else 0L
                        val txRate = if (traffic != null && elapsed >= 250L && tx >= traffic.third) (tx - traffic.third) * 1000L / elapsed else 0L
                        if (traffic == null || elapsed >= 250L) {
                            previousTraffic = Triple(now, rx, tx)
                        }
                        val handshake = stats.optLong("handshake")
                        val nanos = stats.optLong("handshakeNanos")
                        if (fastPolling && (handshake != 0L || nanos != 0L || now - startedAt >= 10_000L)) {
                            fastPolling = false
                        }
                        val currentNetworkEpoch = physicalNetworkEpoch.get()
                        if (currentNetworkEpoch != observedNetworkEpoch) {
                            health = HandshakeHealth(now).also { it.setBaseline(handshake, nanos) }
                            runtimeHealth.resetGrace(now)
                            previousHandshake = handshake to nanos
                            observedNetworkEpoch = currentNetworkEpoch
                            EventLog.add("handshake baseline reset after physical network change")
                        }
                        val observed = when (health.observe(now, handshake, nanos)) {
                            HandshakeHealth.Status.CONNECTING -> ConnectionPhase.CONNECTING
                            HandshakeHealth.Status.CONFIRMED -> ConnectionPhase.CONNECTED
                            HandshakeHealth.Status.WAITING -> ConnectionPhase.WAITING
                        }
                        val physicalAvailable = physicalNetworkAvailable.get()
                        val next = if (physicalAvailable) observed else ConnectionPhase.WAITING
                        val proxyDnsFailures = stats.optLong("proxyDnsFailures")
                        val runtime = runtimeHealth.statisticsSucceeded(now, proxyDnsFailures, health.ageSeconds)
                        val dnsUnavailable = runtime.dnsWarning != null
                        if (dnsUnavailable != previousDnsWarning) {
                            EventLog.add(if (dnsUnavailable) {
                                "tunnel.dns.degraded; TUN retained, no direct fallback; ${runtime.dnsWarning}"
                            } else {
                                "tunnel.dns.recovered; VPN remained active"
                            })
                            previousDnsWarning = dnsUnavailable
                        }
                        if (mutableState.value.phase != next) {
                            EventLog.add("connection.state ${mutableState.value.phase} -> $next; stats=$stats")
                            current.updateNotification(next)
                        }
                        mutableState.update { it.copy(phase = next,
                            error = when {
                                !physicalAvailable -> "Физическая сеть временно недоступна"
                                dnsUnavailable -> "DNS через VPN временно недоступен; VPN остаётся включённым. Проверьте резервный DNS в правилах."
                                else -> null
                            },
                            handshakeAgeSeconds = health.ageSeconds,
                            rxBytes=rx, txBytes=tx, rxBytesPerSecond=rxRate, txBytesPerSecond=txRate, lastHandshakeEpochSeconds=handshake,
                            backendVersion=stats.optString("version"), dnsQueries=stats.optLong("dnsQueries"), dnsErrors=stats.optLong("dnsErrors"), proxyDnsFailures=proxyDnsFailures,
                            dnsCacheHits=stats.optLong("dnsCacheHits"),
                            directFlows=stats.optLong("directFlows"), proxyFlows=stats.optLong("proxyFlows"), blockedFlows=stats.optLong("blockedFlows")) }
                        if ((handshake != 0L || nanos != 0L) && previousHandshake != (handshake to nanos)) {
                            EventLog.add("AWG handshake confirmed: sec=$handshake nsec=$nanos; monotonicMs=$now")
                            if (previousHandshake == null) scope.launch {
                                if (app.routing.bundlePath() == null) app.routing.refresh()
                            }
                            previousHandshake = handshake to nanos
                        }
                        if (runtime.failure != null && !failureScheduled) {
                            failureScheduled = true
                            stopLostTunnel(current, IllegalStateException(runtime.failure))
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        EventLog.error("tunnel.poll; TUN retained, no direct fallback", it)
                        mutableState.update { old -> old.copy(phase = ConnectionPhase.WAITING, error = "${it.javaClass.name}: ${it.message}") }
                        service?.updateNotification(ConnectionPhase.WAITING)
                        if (!failureScheduled && runtimeHealth.statisticsFailed(it) != null) {
                            failureScheduled = true
                            service?.let { current -> stopLostTunnel(current, it) }
                        }
                    }
                }
                delay(if (fastPolling) 150L else 1_000L)
            }
        }
    }
}
