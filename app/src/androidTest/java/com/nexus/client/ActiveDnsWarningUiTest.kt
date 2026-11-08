package com.nexus.client

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexus.client.tunnel.ConnectionPhase
import com.nexus.client.tunnel.TunnelSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic rendering test only: no VpnService, profiles, keys or network connection. */
@RunWith(AndroidJUnit4::class)
class ActiveDnsWarningUiTest {
    @Test
    fun retainedConnectedTunnelShowsWarningAlongsideConnectedStatus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NexusApplication
        val stateField = app.tunnel.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutableState = stateField.get(app.tunnel) as MutableStateFlow<TunnelSnapshot>
        val previousState = mutableState.value
        assertFalse("Synthetic UI test must not replace a running VPN", previousState.tunnelActive)
        val warning = "DNS через VPN временно недоступен; VPN остаётся включённым. Проверьте резервный DNS в правилах."

        try {
            instrumentation.runOnMainSync {
                mutableState.value = TunnelSnapshot(
                    phase = ConnectionPhase.CONNECTED,
                    tunnelActive = true,
                    lastHandshakeEpochSeconds = System.currentTimeMillis() / 1000,
                    error = warning,
                )
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.uptimeMillis() + 15_000
                var visibleText = emptyList<String>()
                do {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    visibleText = root?.let(::visibleTexts).orEmpty()
                    if (visibleText.contains(warning) && visibleText.contains("Подключено")) break
                    SystemClock.sleep(250)
                } while (SystemClock.uptimeMillis() < deadline)

                // FLAG_SECURE intentionally blocks display capture. Drawing this
                // synthetic Activity into its own bitmap keeps privacy enabled.
                var screenshot: Bitmap? = null
                var renderedColours = 0
                var attempts = 0
                while (attempts++ < 20 && renderedColours < 12) {
                    SystemClock.sleep(500)
                    screenshot?.recycle()
                    scenario.onActivity { activity ->
                        val view = activity.window.decorView
                        screenshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(requireNotNull(screenshot)))
                    }
                    val frame = requireNotNull(screenshot)
                    val colours = mutableSetOf<Int>()
                    for (y in 0 until frame.height step 120) {
                        for (x in 0 until frame.width step 120) colours += frame.getPixel(x, y)
                    }
                    renderedColours = colours.size
                }
                assertNotNull("Expected a screenshot of the rendered activity", screenshot)
                requireNotNull(screenshot).let { frame ->
                    try {
                        File(app.cacheDir, "active-dns-warning-api${Build.VERSION.SDK_INT}.png").outputStream().use { output ->
                            assertTrue("PNG screenshot must be saved", frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    } finally {
                        frame.recycle()
                    }
                }
                assertTrue("Rendered home screen must not be blank: colours=$renderedColours", renderedColours >= 12)
                assertTrue("Nonfatal DNS warning must be visible; actual=$visibleText", visibleText.contains(warning))
                assertTrue("Connected status must remain visible; actual=$visibleText", visibleText.contains("Подключено"))
                assertTrue("Disconnect action must remain present; actual=$visibleText", visibleText.contains("Отключить"))
            }
        } finally {
            instrumentation.runOnMainSync { mutableState.value = previousState }
        }
    }

    private fun visibleTexts(node: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        if (node.isVisibleToUser) node.text?.toString()?.let(result::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> result += visibleTexts(child) }
        }
        return result
    }
}
