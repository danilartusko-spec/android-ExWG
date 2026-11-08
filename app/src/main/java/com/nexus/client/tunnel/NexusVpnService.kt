package com.nexus.client.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.nexus.client.EventLog
import com.nexus.client.MainActivity
import com.nexus.client.NexusApplication
import com.nexus.client.R
import mobile.Engine
import mobile.Mobile
import mobile.Protector
import org.amnezia.awg.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.random.Random

class NexusVpnService : VpnService() {
    private data class EngineStartState(
        val userspace: String,
        val addresses: String,
        val mtu: Int,
        val directory: String,
        val overrides: String,
    )
    private data class HandshakeStamp(val seconds: Long, val nanos: Long) {
        val present get() = seconds != 0L || nanos != 0L
    }

    inner class LocalBinder : Binder() { val service get() = this@NexusVpnService }
    private val binder = LocalBinder()
    @Volatile private var engine: Engine? = null
    private var tun: ParcelFileDescriptor? = null
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observingNetwork = false
    private var networkDescription = ""
    private var selectedPhysical: Network? = null
    private var activeConfig: Config? = null
    private var engineStartState: EngineStartState? = null
    private var networkGeneration = 0L
    private var recoveryJob: Job? = null
    private val notifications by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkAvailable(network, "best-available")
        override fun onLost(network: Network) = networkLost(network, "best-lost")
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = networkCapabilitiesChanged(network, caps)
        override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = networkLinksChanged(network, links)
    }

    @Synchronized private fun networkAvailable(physical: Network, reason: String) {
        val previous = selectedPhysical
        selectedPhysical = physical
        EventLog.add("network.callback reason=$reason network=$physical previous=$previous")
        if (engine != null) setUnderlyingNetworks(arrayOf(physical))
        // onCapabilitiesChanged/onLinkPropertiesChanged are ordered after
        // onAvailable and carry race-free details for this best network.
        updateNetworkDescription(reason, physical, null)
        if (previous == physical || engine == null) return
        networkGeneration += 1
        val generation = networkGeneration
        recoveryJob?.cancel()
        (application as NexusApplication).tunnel.physicalNetworkChanged(this, true)
        recoveryJob = networkScope.launch { recoverPhysicalNetwork(physical, generation) }
    }

    @Synchronized private fun networkLost(network: Network, reason: String) {
        EventLog.add("network.callback reason=$reason network=$network selected=$selectedPhysical")
        if (selectedPhysical != network) return
        selectedPhysical = null
        networkGeneration += 1
        recoveryJob?.cancel(); recoveryJob = null
        engine?.setDirectIPv6(false)
        if (engine != null) setUnderlyingNetworks(emptyArray())
        updateNetworkDescription(reason, null, null)
        (application as NexusApplication).tunnel.physicalNetworkChanged(this, false)
        EventLog.add("network.handover waiting: no best physical network")
    }

    @Synchronized private fun networkCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (selectedPhysical != network) {
            EventLog.add("network.callback reason=capabilities-ignored network=$network selected=$selectedPhysical caps=$caps")
            return
        }
        EventLog.add("network.callback reason=capabilities network=$network validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} suspended=${Build.VERSION.SDK_INT >= 28 && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)}")
    }

    @Synchronized private fun networkLinksChanged(network: Network, links: LinkProperties) {
        if (selectedPhysical != network) return
        updateNetworkDescription("links", network, links)
    }

    @Synchronized private fun updateNetworkDescription(reason: String, physical: Network?, links: LinkProperties?) {
        val ipv6 = PhysicalNetwork.hasIPv6(links)
        engine?.setDirectIPv6(ipv6)
        val description = "network=$physical directIPv6=$ipv6 links=$links"
        if (description != networkDescription) {
            networkDescription = description
            EventLog.add("network.physical reason=$reason $description")
        }
    }

    private suspend fun recoverPhysicalNetwork(target: Network, generation: Long) {
        // A different Android Network cannot inherit the protected UDP socket
        // created for the previous transport reliably. On Samsung Android 14,
        // two BindUpdate attempts always timed out and only delayed recovery by
        // roughly eight seconds. Keep the system VPN/TUN and rules, but replace
        // the userspace engine immediately so all physical sockets are created
        // against the new best network.
        val settleDelay = Random.nextLong(160L, 361L)
        delay(settleDelay)
        EventLog.add("network.handover fast-restart selected jitter_ms=$settleDelay; TUN and rules retained")
        repeat(2) { index ->
            if (index > 0) delay(Random.nextLong(650L, 1_101L))
            val endpointSettings = runCatching { resolvePeerEndpoints(target) }
                .onFailure { EventLog.error("network.endpoint.resolve restart=${index + 1}", it) }
                .getOrDefault("")
            val replacement = synchronized(this) {
                if (generation != networkGeneration || selectedPhysical != target) return
                runCatching { restartEngine(target, endpointSettings) }
            }
            if (replacement.isFailure) {
                EventLog.error("network.handover engine-restart attempt=${index + 1}", replacement.exceptionOrNull()!!)
                return@repeat
            }
            val restarted = replacement.getOrThrow()
            EventLog.add("network.handover engine-restarted attempt=${index + 1}; TUN and rules retained")
            if (awaitFreshHandshake(target, generation, restarted, HandshakeStamp(0, 0), 5_750L)) {
                EventLog.add("network.handover recovered mode=engine-restart attempt=${index + 1}")
                drainLogs()
                return
            }
            EventLog.add("network.handover engine-restart-unconfirmed attempt=${index + 1} timeout_ms=5750")
        }
        EventLog.add("network.handover waiting: automatic recovery exhausted; TUN retained, no direct fallback")
    }

    private suspend fun awaitFreshHandshake(
        target: Network,
        generation: Long,
        expected: Engine,
        baseline: HandshakeStamp,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(100)
            val current = synchronized(this) {
                if (generation != networkGeneration || selectedPhysical != target || engine !== expected) null else engine
            } ?: return false
            val stamp = runCatching { handshakeStamp(current) }
                .onFailure { EventLog.error("network.handshake.read", it) }
                .getOrNull() ?: continue
            if (stamp.present && stamp != baseline) return true
        }
        return false
    }

    private fun handshakeStamp(source: Engine): HandshakeStamp {
        val stats = JSONObject(source.statistics())
        check(stats.optBoolean("running")) { "AWG engine running=false while waiting for handover" }
        check(!stats.has("statisticsError")) { stats.getString("statisticsError") }
        return HandshakeStamp(stats.optLong("handshake"), stats.optLong("handshakeNanos"))
    }

    @Synchronized private fun restartEngine(target: Network, endpointSettings: String): Engine {
        val state = requireNotNull(engineStartState) { "network restart state unavailable" }
        engine?.let { previous ->
            previous.close()
            persistLogs(previous)
        }
        engine = null
        val replacement = createEngine(state, target, endpointSettings)
        engine = replacement
        return replacement
    }

    private fun createEngine(state: EngineStartState, physical: Network?, endpointSettings: String = ""): Engine {
        val descriptor = requireNotNull(tun) { "TUN descriptor unavailable" }
        val fd = ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd()
        val effectiveSettings = if (endpointSettings.isBlank()) state.userspace else state.userspace.trimEnd() + "\n" + endpointSettings
        return Mobile.startWithNetwork(fd.toLong(), effectiveSettings, state.addresses, state.mtu.toLong(), state.directory, state.overrides,
            object : Protector {
                override fun protect(fd: Long): Boolean {
                    if (!this@NexusVpnService.protect(fd.toInt())) {
                        EventLog.add("network.socket protect=false fd=$fd")
                        return false
                    }
                    if (physical == null) return true
                    return try {
                        // fromFd duplicates the descriptor, so closing the wrapper
                        // cannot close the Go-owned UDP socket. Binding either
                        // descriptor updates the shared socket before it connects.
                        ParcelFileDescriptor.fromFd(fd.toInt()).use { duplicate ->
                            physical.bindSocket(duplicate.fileDescriptor)
                        }
                        EventLog.add("network.socket bound network=$physical fd=$fd")
                        true
                    } catch (error: Throwable) {
                        EventLog.error("network.socket.bind network=$physical fd=$fd", error)
                        // Some OEM VPN stacks reject explicit netId binding
                        // with EPERM even after protect(). The protected socket
                        // still has the safe Android default-underlay path, so
                        // keep the tunnel alive and let AWG retransmit instead
                        // of turning an optional acceleration into an outage.
                        true
                    }
                }
            },
            PhysicalNetwork.hasIPv6(connectivity, physical))
    }

    private fun resolvePeerEndpoints(network: Network): String {
        val config = activeConfig ?: return ""
        return buildString {
            for (peer in config.peers) {
                val endpoint = peer.endpoint.orElse(null) ?: continue
                val addresses = network.getAllByName(endpoint.host)
                val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
                    ?: error("Endpoint DNS returned no addresses")
                val raw = address.hostAddress?.substringBefore('%') ?: error("Endpoint address unavailable")
                val rendered = if (address is Inet6Address) "[$raw]" else raw
                append("public_key=").append(peer.publicKey.toHex()).append('\n')
                append("endpoint=").append(rendered).append(':').append(endpoint.port).append('\n')
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel("nexus_vpn", "VPN-подключение", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionPhase.CONNECTING))
        EventLog.add("VpnService запущен, foreground-уведомление создано")
        connectivity.requestNetwork(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
        observingNetwork = true
    }

    private fun buildNotification(phase: ConnectionPhase): Notification {
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "nexus_vpn") else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val icon = when (phase) {
            ConnectionPhase.CONNECTED -> R.drawable.ic_vpn_connected
            ConnectionPhase.DISCONNECTED, ConnectionPhase.DISCONNECTING, ConnectionPhase.ERROR -> R.drawable.ic_vpn_notification
            else -> R.drawable.ic_vpn_waiting
        }
        val text = when (phase) {
            ConnectionPhase.CONNECTED -> "VPN подключён"
            ConnectionPhase.CONNECTING -> "VPN подключается"
            ConnectionPhase.WAITING -> "Соединение нестабильно — откройте Ex WG"
            ConnectionPhase.DISCONNECTING -> "VPN отключается"
            ConnectionPhase.ERROR -> "Ошибка VPN — откройте Ex WG"
            ConnectionPhase.DISCONNECTED -> "VPN отключён"
        }
        return notification.setSmallIcon(icon).setContentTitle("Ex WG")
            .setContentText(text).setContentIntent(open).setOngoing(true).build()
    }

    fun updateNotification(phase: ConnectionPhase) {
        notifications.notify(NOTIFICATION_ID, buildNotification(phase))
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object { private const val NOTIFICATION_ID = 9102 }

    @Synchronized fun start(config: Config, userspace: String, directory: String, overrides: String) {
        check(engine == null) { "VPN уже запущен" }
        Mobile.configureLegacyTrust(Build.VERSION.SDK_INT < 26)
        val mtu = config.`interface`.mtu.orElse(1280)
        require(mtu in 1280..1500) { "MTU должен быть от 1280 до 1500" }
        val builder = Builder().setSession("Ex WG").setMtu(mtu).setBlocking(false)
            .addAddress("10.111.0.1", 32).addAddress("fd6e:6578:7573:ffff::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer("10.111.0.2")
        val descriptor = builder.establish() ?: error("Система не создала TUN: VPN-разрешение отозвано")
        tun = descriptor
        try {
            EventLog.add("TUN создан, IPv4/IPv6 захвачены, внутренний DNS назначен")
            activeConfig = config
            val physical = selectedPhysical ?: PhysicalNetwork.select(connectivity)
            selectedPhysical = physical
            engineStartState = EngineStartState(userspace, config.`interface`.addresses.joinToString(","), mtu, directory, overrides)
            engine = createEngine(requireNotNull(engineStartState), physical)
            setUnderlyingNetworks(physical?.let { arrayOf(it) } ?: emptyArray())
            updateNetworkDescription("engine-start", physical, physical?.let(connectivity::getLinkProperties))
            EventLog.add("Официальный AWG 3.1 запущен, DNS/Direct/Proxy/Block применены")
        } catch (error: Throwable) { activeConfig = null; engineStartState = null; descriptor.close(); tun = null; throw error }
    }

    /** Replaces only the userspace AWG/router engine. Android keeps the same TUN and fail-closed routes. */
    @Synchronized fun switchProfile(config: Config, userspace: String, directory: String, overrides: String) {
        val previousEngine = requireNotNull(engine) { "VPN engine unavailable" }
        val previousConfig = requireNotNull(activeConfig) { "Previous VPN config unavailable" }
        val previousState = requireNotNull(engineStartState) { "Previous engine state unavailable" }
        val requestedMtu = config.`interface`.mtu.orElse(1280)
        require(requestedMtu in 1280..1500) { "MTU должен быть от 1280 до 1500" }
        val nextState = EngineStartState(
            userspace = userspace,
            addresses = config.`interface`.addresses.joinToString(","),
            // Builder.setMtu cannot be changed without replacing Android's TUN.
            // Keep the established MTU so the hot switch remains fail-closed.
            mtu = previousState.mtu,
            directory = directory,
            overrides = overrides,
        )
        if (requestedMtu != previousState.mtu) EventLog.add("profile.hotSwitch requested_mtu=$requestedMtu retained_tun_mtu=${previousState.mtu}")
        recoveryJob?.cancel(); recoveryJob = null
        previousEngine.close()
        persistLogs(previousEngine)
        engine = null
        try {
            val replacement = createEngine(nextState, selectedPhysical)
            activeConfig = config
            engineStartState = nextState
            engine = replacement
            setUnderlyingNetworks(selectedPhysical?.let { arrayOf(it) } ?: emptyArray())
            EventLog.add("profile.hotSwitch engine replaced; TUN and system VPN retained")
        } catch (switchError: Throwable) {
            EventLog.error("profile.hotSwitch.new", switchError)
            try {
                val rollback = createEngine(previousState, selectedPhysical)
                activeConfig = previousConfig
                engineStartState = previousState
                engine = rollback
                setUnderlyingNetworks(selectedPhysical?.let { arrayOf(it) } ?: emptyArray())
                EventLog.add("profile.hotSwitch previous engine restored")
            } catch (rollbackError: Throwable) {
                activeConfig = null
                engineStartState = null
                engine = null
                switchError.addSuppressed(rollbackError)
                EventLog.error("profile.hotSwitch.rollback", rollbackError)
            }
            throw switchError
        }
    }

    @Synchronized fun statistics(): String = engine?.statistics() ?: "{\"running\":false}"
    @Synchronized fun hasPhysicalNetwork(): Boolean = selectedPhysical != null || PhysicalNetwork.select(connectivity) != null
    @Synchronized fun requestHandshake() { engine?.requestHandshake() ?: error("AWG engine is absent") }
    private fun persistLogs(source: Engine) {
        val raw = source.drainLogs()
        if (!EventLog.isEnabled) return
        val messages = org.json.JSONArray(raw)
        if (messages.length() > 0) EventLog.add((0 until messages.length()).joinToString("\n") { messages.getString(it) })
    }
    @Synchronized fun drainLogs() { engine?.let(::persistLogs) }
    fun probeDomain(domain: String): String = engine?.probeDomain(domain) ?: "{\"ok\":false}"
    fun probeDNS(): String = engine?.probeDNS() ?: "{}"
    fun checkRoute(target: String): String = engine?.checkRoute(target) ?: "{\"error\":\"VPN не подключен\"}"
    fun fetchResource(source: String, limit: Long, allowHapp: Boolean): ByteArray =
        engine?.fetchResource(source, limit, allowHapp) ?: error("VPN engine unavailable for routing download")
    fun fetchResourceDirect(source: String, limit: Long, allowHapp: Boolean): ByteArray =
        engine?.fetchResourceDirect(source, limit, allowHapp) ?: error("VPN engine unavailable for direct routing download")
    @Synchronized fun updateRules(directory: String, overrides: String) { engine?.updateRules(directory, overrides) }
    @Synchronized fun stopTunnel() {
        recoveryJob?.cancel(); recoveryJob = null
        try { engine?.close(); drainLogs() }
        finally { engine = null; activeConfig = null; engineStartState = null; val descriptor = tun; tun = null; descriptor?.close() }
    }
    override fun onRevoke() {
        EventLog.add("VPN-разрешение отозвано системой")
        (application as NexusApplication).tunnel.systemStopped("VPN-разрешение отозвано", this)
        super.onRevoke()
    }
    override fun onDestroy() {
        if (observingNetwork) { connectivity.unregisterNetworkCallback(networkCallback); observingNetwork = false }
        networkScope.cancel()
        stopTunnel()
        (application as NexusApplication).tunnel.serviceDestroyed(this)
        super.onDestroy()
    }
}
