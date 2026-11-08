package com.nexus.client.core.awg

/** One instance per NEW engine. Epoch is an identity token, never a timeout clock. */
class HandshakeHealth(private val startedMs: Long) {
    enum class Status { CONNECTING, CONFIRMED, WAITING }
    private var token: Pair<Long, Long>? = null
    private var observedMs: Long? = null
    var ageSeconds: Long? = null
        private set

    /** Ignore an already confirmed handshake after physical-network handover. */
    fun setBaseline(seconds: Long, nanos: Long) {
        token = seconds to nanos
        observedMs = null
        ageSeconds = null
    }

    fun observe(nowMs: Long, seconds: Long, nanos: Long): Status {
        val current = seconds to nanos
        if (current != (0L to 0L) && current != token) {
            token = current
            observedMs = nowMs
        }
        ageSeconds = observedMs?.let { ((nowMs - it).coerceAtLeast(0)) / 1000 }
        return when {
            observedMs == null -> if (nowMs - startedMs < 30_000) Status.CONNECTING else Status.WAITING
            else -> Status.CONFIRMED
        }
    }
}
