package com.nexus.client.core

import com.nexus.client.core.awg.HandshakeHealth
import com.nexus.client.core.awg.HandshakeHealth.Status.*
import org.junit.Assert.*
import org.junit.Test

class HandshakeHealthTest {
    @Test fun noHandshakeNeverConnectsAndLateReplyRecovers() {
        val health = HandshakeHealth(100)
        assertEquals(CONNECTING, health.observe(100, 0, 0))
        assertEquals(WAITING, health.observe(30100, 0, 0))
        assertEquals(CONFIRMED, health.observe(120100, 123, 456))
    }
    @Test fun confirmedSessionStaysConnectedWhileNativeCoreOwnsRekey() {
        val health = HandshakeHealth(0)
        assertEquals(CONFIRMED, health.observe(0, 100, 7))
        assertEquals(CONFIRMED, health.observe(60000, 100, 7))
        assertEquals(CONFIRMED, health.observe(151000, 100, 7))
        assertEquals(CONFIRMED, health.observe(152000, 1, 2)) // wall clock moved back
        assertEquals(CONFIRMED, health.observe(153000, 1, 3)) // same second, new handshake
        assertEquals(0L, health.ageSeconds)
    }
    @Test fun newSessionHasNoStaleState() {
        assertEquals(CONNECTING, HandshakeHealth(100).observe(100, 0, 0))
    }

    @Test fun physicalHandoverRequiresAHandshakeNewerThanBaseline() {
        val health = HandshakeHealth(10_000)
        health.setBaseline(500, 7)
        assertEquals(CONNECTING, health.observe(10_000, 500, 7))
        assertEquals(WAITING, health.observe(40_000, 500, 7))
        assertEquals(CONFIRMED, health.observe(40_001, 500, 8))
        assertEquals(0L, health.ageSeconds)
    }
}
