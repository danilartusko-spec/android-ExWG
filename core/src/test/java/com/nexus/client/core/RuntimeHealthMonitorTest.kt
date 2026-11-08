package com.nexus.client.core

import com.nexus.client.core.awg.RuntimeHealthMonitor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHealthMonitorTest {
    @Test fun idleTunnelNeverFailsWithoutRealProxyErrors() {
        val health = RuntimeHealthMonitor(0)
        val result = health.statisticsSucceeded(10 * 60_000L, 0, 10 * 60L)
        assertNull(result.failure)
        assertNull(result.dnsWarning)
    }

    @Test fun threeStatisticsFailuresEndTheSession() {
        val health = RuntimeHealthMonitor(0)
        val error = IllegalStateException("UAPI unavailable")
        assertNull(health.statisticsFailed(error))
        assertNull(health.statisticsFailed(error))
        assertNotNull(health.statisticsFailed(error))
    }

    @Test fun proxyDnsFailureWithOldHandshakeWarnsWithoutEndingSession() {
        val health = RuntimeHealthMonitor(0)
        val result = health.statisticsSucceeded(40_000, 5, 203)
        assertNull("DNS failure must not tear down a working local engine", result.failure)
        assertNotNull(result.dnsWarning)
        assertTrue(result.dnsWarning!!.contains("5"))
    }

    @Test fun proxyDnsFailureWithoutAnyHandshakeKeepsTunnelForRecovery() {
        val result = RuntimeHealthMonitor(0).statisticsSucceeded(40_000, 3, null)
        assertNull(result.failure)
        assertNotNull(result.dnsWarning)
    }

    @Test fun freshHandshakeDoesNotHideDnsFailure() {
        val result = RuntimeHealthMonitor(0).statisticsSucceeded(40_000, 3, 10)
        assertNull(result.failure)
        assertNotNull(result.dnsWarning)
    }

    @Test fun successfulDnsClearsWarning() {
        val health = RuntimeHealthMonitor(0)
        assertNotNull(health.statisticsSucceeded(40_000, 5, 203).dnsWarning)
        val recovered = health.statisticsSucceeded(41_500, 0, 204)
        assertNull(recovered.failure)
        assertNull(recovered.dnsWarning)
    }

    @Test fun successfulStatisticsResetLocalFailureStreakEvenWhenDnsFails() {
        val health = RuntimeHealthMonitor(0)
        val error = IllegalStateException("UAPI unavailable")
        health.statisticsFailed(error)
        health.statisticsFailed(error)
        health.statisticsSucceeded(40_000, 5, 203)
        assertNull(health.statisticsFailed(error))
    }

    @Test fun handoverStartsANewGracePeriod() {
        val health = RuntimeHealthMonitor(0)
        health.resetGrace(100_000)
        assertNull(health.statisticsSucceeded(110_000, 5, null).dnsWarning)
        val result = health.statisticsSucceeded(131_000, 5, null)
        assertNull(result.failure)
        assertNotNull(result.dnsWarning)
    }

    @Test fun handoverGraceDoesNotPretendThatKnownDnsFailureRecovered() {
        val health = RuntimeHealthMonitor(0)
        assertNotNull(health.statisticsSucceeded(40_000, 5, 203).dnsWarning)
        health.resetGrace(50_000)
        assertNotNull(health.statisticsSucceeded(51_000, 5, null).dnsWarning)
        assertNull(health.statisticsSucceeded(52_000, 0, null).dnsWarning)
    }
}
