package com.nexus.client.core.awg

/** Local engine health and nonfatal DNS degradation are separate observations. */
class RuntimeHealthMonitor(private var graceStartedMs: Long) {
    data class PollResult(val failure: String? = null, val dnsWarning: String? = null)
    private var statisticsFailures = 0
    private var dnsDegraded = false

    fun resetGrace(nowMs: Long) {
        graceStartedMs = nowMs
        statisticsFailures = 0
    }

    fun statisticsFailed(error: Throwable): String? {
        statisticsFailures++
        return if (statisticsFailures >= MAX_STATISTICS_FAILURES) {
            "VPN-движок не отвечает $statisticsFailures проверки подряд: ${error.javaClass.name}: ${error.message}"
        } else null
    }

    fun statisticsSucceeded(nowMs: Long, proxyDnsFailures: Long, handshakeAgeSeconds: Long?): PollResult {
        statisticsFailures = 0
        if (proxyDnsFailures < MAX_PROXY_DNS_FAILURES) {
            dnsDegraded = false
            return PollResult()
        }
        // A network change starts a grace period, not a successful DNS lookup.
        // Keep an already observed failure visible until the failure streak resets.
        if (!dnsDegraded && nowMs - graceStartedMs < STARTUP_GRACE_MS) return PollResult()
        dnsDegraded = true
        // A failed resolver and an old handshake do not prove a dead engine:
        // authenticated peer traffic can still arrive between handshakes.
        val handshake = handshakeAgeSeconds?.let { "последний handshake $it сек назад" } ?: "handshake не подтверждён"
        return PollResult(dnsWarning = "Proxy DNS недоступен $proxyDnsFailures запросов подряд; $handshake")
    }

    companion object {
        const val MAX_STATISTICS_FAILURES = 3
        const val MAX_PROXY_DNS_FAILURES = 3L
        const val STARTUP_GRACE_MS = 30_000L
    }
}
