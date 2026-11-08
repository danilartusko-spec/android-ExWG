package com.nexus.client.ui

import com.nexus.client.tunnel.ConnectionPhase
import com.nexus.client.tunnel.TunnelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveTunnelWarningTest {
    @Test
    fun connectedTunnelShowsDnsWarningWithoutReplacingStatus() {
        val snapshot = TunnelSnapshot(
            phase = ConnectionPhase.CONNECTED,
            tunnelActive = true,
            error = "DNS через VPN временно недоступен. Туннель сохранён.",
        )

        assertEquals("DNS через VPN временно недоступен. Туннель сохранён.", activeTunnelWarning(snapshot))
    }

    @Test
    fun waitingTunnelShowsDnsWarningWhileRetained() {
        val snapshot = TunnelSnapshot(
            phase = ConnectionPhase.WAITING,
            tunnelActive = true,
            error = "Proxy DNS недоступен 5 запросов подряд",
        )

        assertEquals("Proxy DNS недоступен 5 запросов подряд", activeTunnelWarning(snapshot))
    }

    @Test
    fun terminalErrorDoesNotDuplicatePrimaryErrorStatus() {
        val snapshot = TunnelSnapshot(
            phase = ConnectionPhase.ERROR,
            tunnelActive = true,
            error = "Ошибка соединения",
        )

        assertNull(activeTunnelWarning(snapshot))
    }

    @Test
    fun disconnectedTunnelDoesNotShowStaleWarning() {
        val snapshot = TunnelSnapshot(
            phase = ConnectionPhase.DISCONNECTED,
            tunnelActive = false,
            error = "Proxy DNS недоступен 5 запросов подряд",
        )

        assertNull(activeTunnelWarning(snapshot))
    }

    @Test
    fun activeTunnelWithoutErrorDoesNotShowWarning() {
        val snapshot = TunnelSnapshot(phase = ConnectionPhase.CONNECTED, tunnelActive = true)

        assertNull(activeTunnelWarning(snapshot))
    }

    @Test
    fun blankErrorDoesNotReserveWarningSpace() {
        val snapshot = TunnelSnapshot(
            phase = ConnectionPhase.CONNECTED,
            tunnelActive = true,
            error = " \n ",
        )

        assertNull(activeTunnelWarning(snapshot))
    }
}
