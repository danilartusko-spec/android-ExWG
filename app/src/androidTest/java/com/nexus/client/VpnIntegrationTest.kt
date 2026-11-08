package com.nexus.client

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexus.client.tunnel.ConnectionPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class VpnIntegrationTest {
    @Test fun routingSettingsAreGlobalAndProfilesStayNewestFirst() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NexusApplication
        val previousSettings = app.profiles.routingSettings()
        val raw = """
            [Interface]
            PrivateKey = L1+KUOmDj0arOKcCtfn9eEkqN9IogQd4M4N2QisQwmk=
            Address = 10.20.0.2/32
            [Peer]
            PublicKey = 2F67N6AYM1moe9RzFOQdHsePdoD4Cd2Wf/VLGUBREWk=
            Endpoint = vpn.example:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val older = app.profiles.create("Older", raw)
        val newer = app.profiles.create("Newer", raw)
        try {
            app.profiles.saveRoutingSettings(com.nexus.client.data.RoutingSettings("https://example.com/routing", "proxy domain:example.com"))
            val created = app.profiles.list().filter { it.id == older.id || it.id == newer.id }
            assertEquals(listOf(newer.id, older.id), created.map { it.id })
            assertTrue(created.all { it.routingUrl == "https://example.com/routing" })
            assertTrue(created.all { it.overrides == "proxy domain:example.com" })
        } finally {
            app.profiles.saveRoutingSettings(previousSettings)
            app.profiles.delete(older.id)
            app.profiles.delete(newer.id)
        }
    }

    @Test fun multipleProfileSwitcherIsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NexusApplication
        val previousId = app.profiles.activeId()
        val raw = """
            [Interface]
            PrivateKey = L1+KUOmDj0arOKcCtfn9eEkqN9IogQd4M4N2QisQwmk=
            Address = 10.20.0.2/32
            [Peer]
            PublicKey = 2F67N6AYM1moe9RzFOQdHsePdoD4Cd2Wf/VLGUBREWk=
            Endpoint = vpn.example:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val first = app.profiles.create("01 · Основной", raw)
        val second = app.profiles.create("02 · Резервный", raw)
        app.profiles.select(first.id)
        try {
            shell(instrumentation, "input keyevent KEYCODE_WAKEUP")
            shell(instrumentation, "wm dismiss-keyguard")
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                // First Compose frame on the API 21 software renderer can take
                // several seconds after a fresh install. Wait for actual UI
                // colours instead of accepting the opaque window background.
                var screenshot: android.graphics.Bitmap? = null
                var renderedColours = 0
                var attempts = 0
                while (attempts++ < 20 && renderedColours < 12) {
                    Thread.sleep(500)
                    screenshot?.recycle()
                    it.onActivity { activity ->
                        val view = activity.window.decorView
                        screenshot = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(screenshot!!))
                    }
                    val frame = requireNotNull(screenshot)
                    val colours = mutableSetOf<Int>()
                    for (y in 0 until frame.height step 120) for (x in 0 until frame.width step 120) colours += frame.getPixel(x, y)
                    renderedColours = colours.size
                }
                val frame = requireNotNull(screenshot)
                java.io.File(app.cacheDir, "profile-switcher-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                    frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue("Rendered home screen must not be blank: colours=$renderedColours", renderedColours >= 12)
                assertEquals(listOf("02 · Резервный", "01 · Основной"), app.profiles.list().filter { profile -> profile.id == first.id || profile.id == second.id }.map { profile -> profile.name })
                assertEquals(first.id, app.profiles.activeId())
                frame.recycle()
            }
        } finally {
            app.profiles.delete(first.id)
            app.profiles.delete(second.id)
            previousId?.let { runCatching { app.profiles.select(it) } }
        }
    }

    @Test fun launcherIconHasTransparentCornersAndHomeOpens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = android.graphics.Bitmap.createBitmap(216, 216, android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 216, 216)
        icon.draw(android.graphics.Canvas(bitmap))
        assertEquals("Launcher icon must not have opaque square corners", 0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
        assertTrue("Icon centre must be visible", android.graphics.Color.alpha(bitmap.getPixel(108, 108)) > 0)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            assertTrue("Modern launchers need an adaptive icon", icon is android.graphics.drawable.AdaptiveIconDrawable)
        }
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            instrumentation.waitForIdleSync()
            home.onActivity { assertFalse(it.isFinishing) }
        }
        bitmap.recycle()
    }

    @Test fun keystoreServiceTunHandshakeAndLifecycle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val encoded = InstrumentationRegistry.getArguments().getString("fixtureConfig")
        assumeTrue("Run with local core-go/cmd/testserver fixture", encoded != null)
        val raw = String(Base64.getDecoder().decode(encoded))
        val app = instrumentation.targetContext.applicationContext as NexusApplication
        EventLog.setEnabled(true)
        val previousId = app.profiles.activeId()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val meta = app.profiles.create("Nexus instrumentation fixture", raw)
        try {
            assertEquals(raw, app.profiles.readConfig(meta.id))
            assertEquals(raw, com.nexus.client.data.ProfileStore(app).readConfig(meta.id))
            val file = java.io.File(app.filesDir, "profiles/${meta.id}.bin")
            val original = file.readBytes()
            val changed = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            try {
                file.writeBytes(changed)
                assertTrue("Tampered ciphertext must be rejected", runCatching { app.profiles.readConfig(meta.id) }.isFailure)
            } finally { file.writeBytes(original) }
            val encrypted = java.io.File(app.filesDir, "profiles/${meta.id}.bin").readBytes()
            assertFalse(String(encrypted).contains("PrivateKey"))
            app.routing.saveSettings(meta.copy(routingUrl = """{"Name":"Local test","GlobalProxy":true,"DirectSites":["domain:direct.example","domain:app-direct.example"],"DnsHosts":{"echo.example":"10.80.0.1","direct.example":"10.0.2.2"},"BlockSites":["domain:blocked.example"]}"""))
            app.routing.refresh(app.profiles.active()!!).getOrThrow()
            val cachedPath = app.routing.bundlePath(app.profiles.active()!!)
            assertTrue(app.routing.refresh(app.profiles.active()!!.copy(routingUrl = "{broken")).isFailure)
            assertEquals(cachedPath, app.routing.bundlePath(app.profiles.active()!!))
            // Repository job must survive the caller/UI going away.
            app.routing.startUpdate(app.profiles.active()!!.copy(overrides = "block domain:local-override.example"), download = false)
            kotlinx.coroutines.withTimeout(10000) { while (app.routing.operation.value?.busy == true) delay(20) }
            app.routing.startUpdate(app.profiles.active()!!, rebuild = true)
            scenario.recreate()
            kotlinx.coroutines.withTimeout(10000) { while (app.routing.operation.value?.busy == true) delay(20) }
            assertEquals("Rebuild must reset local rules", "", app.profiles.active()!!.overrides)
            assertEquals("Rebuild must preserve VPN keys", raw, app.profiles.readConfig(meta.id))
            assertNotEquals("Rebuild must use a fresh bundle", cachedPath, app.routing.bundlePath(app.profiles.active()!!))
            assertTrue(app.profiles.active()!!.routingStatus.startsWith("Успешно"))
            approveVpnIfRequired(instrumentation, app, scenario)
            app.tunnel.connect(raw).getOrThrow()
            kotlinx.coroutines.withTimeout(20000) { while (app.tunnel.state.value.phase != ConnectionPhase.CONNECTED) delay(100) }
            assertEquals(ConnectionPhase.CONNECTED, app.tunnel.state.value.phase)
            delay(1500) // ConnectivityService publishes the new VPN DNS asynchronously.
            val address = try { InetAddress.getByName("echo.example") } catch (error: Throwable) {
                throw AssertionError("System DNS failed; router state: ${app.tunnel.state.value}", error)
            }
            assertEquals("FakeDNS=false must return a real address", "10.80.0.1", address.hostAddress)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, 8080), 15000)
                socket.soTimeout = 15000
                socket.getOutputStream().write("nexus-android-test".toByteArray())
                val reply = ByteArray(18)
                val n = socket.getInputStream().read(reply)
                assertEquals("nexus-android-test", String(reply, 0, n))
            }
            if (InstrumentationRegistry.getArguments().getString("separateApp") == "true") {
                shell(instrumentation, "am force-stop com.nexus.trafficprobe")
                val reply = kotlinx.coroutines.CompletableDeferred<android.os.Bundle>()
                val receiver = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onReceiveResult(code: Int, data: android.os.Bundle) { reply.complete(data) }
                }
                // Marshal as the framework class, not a test-only anonymous
                // subclass absent from the receiving application's classpath.
                val parcel = android.os.Parcel.obtain()
                val remoteReceiver = try {
                    receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0)
                    android.os.ResultReceiver.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                val request = android.content.Intent().setClassName("com.nexus.trafficprobe", "com.nexus.trafficprobe.ProbeActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reply", remoteReceiver)
                    .putExtra("directTcp", InstrumentationRegistry.getArguments().getString("directTcp")!!.toInt())
                    .putExtra("directUdp", InstrumentationRegistry.getArguments().getString("directUdp")!!.toInt())
                app.startActivity(request)
                val result = kotlinx.coroutines.withTimeout(60000) { reply.await() }
                EventLog.add("TEST.separateApp uid=${result.getInt("uid")} ok=${result.getBoolean("ok")} detail=${result.getString("detail")}")
                assertNotEquals("Test must use a genuinely different UID", android.os.Process.myUid(), result.getInt("uid"))
                assertTrue(result.getString("detail"), result.getBoolean("ok"))
            }
            delay(1800)
            assertTrue(app.tunnel.state.value.lastHandshakeEpochSeconds > 0)
            assertTrue(app.tunnel.state.value.rxBytes > 0)
            if (InstrumentationRegistry.getArguments().getString("healthChecks") == "true") {
                val before = app.tunnel.state.value.lastHandshakeEpochSeconds
                delay(5_000)
                assertEquals(ConnectionPhase.CONNECTED, app.tunnel.state.value.phase)
                assertTrue("native AWG timers must keep the same TUN", app.tunnel.state.value.tunnelActive)
                assertTrue(app.tunnel.state.value.lastHandshakeEpochSeconds >= before)
                assertFalse("Nexus must not add a periodic handshake pattern",
                    EventLog.entries.value.any { it.contains("handshake.refresh requested") })
            }
            scenario.recreate()
            assertEquals(ConnectionPhase.CONNECTED, app.tunnel.state.value.phase)
            if (InstrumentationRegistry.getArguments().getString("liveRouting") == "true") {
                val live = app.profiles.active()!!.copy(routingUrl = "https://routing.help/")
                app.routing.saveSettings(live)
                app.routing.refresh(live).getOrThrow()
                assertNotNull(app.routing.cached(app.profiles.active()!!))
                assertEquals(ConnectionPhase.CONNECTED, app.tunnel.state.value.phase)
                assertTrue("Active VPN update must use a protected socket instead of Android Network.bindSocket",
                    EventLog.entries.value.any { it.contains("routing.download route=protected-direct") })
                val summary = app.routing.summary(app.profiles.active()!!)
                assertFalse("Live profile FakeDNS=false must be preserved", summary.getBoolean("fakeDNS"))
                assertTrue(summary.getLong("geoip.dat.bytes") > 0)
                if (InstrumentationRegistry.getArguments().getString("liveDns") == "true") {
                    val dns = app.tunnel.probeDNS()
                    assertTrue("Android Domestic DoH must work: $dns", dns.getJSONObject("direct").getBoolean("ok"))
                    // The local fixture deliberately has no Internet forwarding;
                    // Remote DNS failure here must NOT be hidden by Domestic success.
                    assertFalse("Fixture must not route external DNS", dns.getJSONObject("proxy").getBoolean("ok"))
                }
                val before = app.routing.bundlePath(app.profiles.active()!!)
                assertTrue(app.routing.refresh(live.copy(routingUrl = "https://127.1/")).isFailure)
                assertEquals(before, app.routing.bundlePath(app.profiles.active()!!))
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) shell(instrumentation, "appops set ${instrumentation.targetContext.packageName} ACTIVATE_VPN deny")
            // AppOps changes authorization but does not invoke onRevoke on all
            // Android releases. Exercise the exact framework callback separately.
            val field = app.tunnel.javaClass.getDeclaredField("service").apply { isAccessible = true }
            val vpnService = field.get(app.tunnel) as com.nexus.client.tunnel.NexusVpnService
            instrumentation.runOnMainSync { vpnService.onRevoke() }
            kotlinx.coroutines.withTimeout(10000) { while (app.tunnel.state.value.phase != ConnectionPhase.DISCONNECTED) delay(100) }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                assertFalse(app.tunnel.hasPermission())
                shell(instrumentation, "appops set ${instrumentation.targetContext.packageName} ACTIVATE_VPN allow")
            }
            approveVpnIfRequired(instrumentation, app, scenario)
            delay(300)
            app.tunnel.connect(raw).getOrThrow()
            app.tunnel.disconnect().getOrThrow()
            assertEquals(ConnectionPhase.DISCONNECTED, app.tunnel.state.value.phase)
            if (InstrumentationRegistry.getArguments().getString("healthChecks") == "true") {
                val unreachable = raw.replace(Regex("(?m)^Endpoint\\s*=.*$"), "Endpoint = 127.0.0.1:9")
                app.tunnel.connect(unreachable).getOrThrow()
                val deadline = android.os.SystemClock.elapsedRealtime() + 33_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    assertNotEquals(ConnectionPhase.CONNECTED, app.tunnel.state.value.phase)
                    assertTrue(app.tunnel.state.value.tunnelActive)
                    delay(300)
                }
                assertEquals(ConnectionPhase.WAITING, app.tunnel.state.value.phase)
                assertEquals(0L, app.tunnel.state.value.lastHandshakeEpochSeconds)
                app.tunnel.disconnect().getOrThrow()
                assertFalse(app.tunnel.state.value.tunnelActive)
            }
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 23) shell(instrumentation, "appops set ${instrumentation.targetContext.packageName} ACTIVATE_VPN allow")
            app.tunnel.disconnect()
            app.profiles.delete(meta.id)
            previousId?.let { app.profiles.select(it) }
            scenario.close()
        }
    }

    @Test fun qrDecoderAndCameraActivityOnThisAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val encoded = InstrumentationRegistry.getArguments().getString("fixtureConfig")
        assumeTrue(encoded != null)
        val raw = String(Base64.getDecoder().decode(encoded))
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(raw, com.google.zxing.BarcodeFormat.QR_CODE, 800, 800)
        val pixels = IntArray(800 * 800) { index -> if (matrix[index % 800, index / 800]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(800, 800, pixels)))
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text
        val imported = com.nexus.client.core.awg.ConfigImport.fromQr(decoded)
        assertEquals(raw.trim(), imported.config.trim())
        if (android.os.Build.VERSION.SDK_INT >= 23) shell(instrumentation, "pm grant ${instrumentation.targetContext.packageName} android.permission.CAMERA")
        val intent = com.journeyapps.barcodescanner.ScanOptions().setCaptureActivity(QrScanActivity::class.java).setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE).createScanIntent(instrumentation.targetContext)
        ActivityScenario.launch<QrScanActivity>(intent).use { camera ->
            instrumentation.waitForIdleSync()
            camera.onActivity { assertFalse(it.isFinishing) }
        }
    }

    private fun shell(instrumentation: android.app.Instrumentation, command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private suspend fun approveVpnIfRequired(instrumentation: android.app.Instrumentation, app: NexusApplication, scenario: ActivityScenario<MainActivity>) {
        val intent = android.net.VpnService.prepare(app) ?: return
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        // Lollipop's ConfirmDialog needs getCallingPackage(), populated only
        // when started for result by an Activity (not by the Application).
        scenario.onActivity { it.startActivityForResult(intent, 777) }
        kotlinx.coroutines.withTimeout(20_000) {
            while (!app.tunnel.hasPermission()) {
                val root = automation.rootInActiveWindow
                if (root?.packageName == "com.android.vpndialogs") {
                    root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                delay(300)
            }
        }
    }
}
