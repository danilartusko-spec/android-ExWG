package com.nexus.client.core

import com.nexus.client.core.awg.AwgConfigParser
import com.nexus.client.core.awg.AwgGeneration
import com.nexus.client.core.cache.ValidatedCache
import com.nexus.client.core.routing.OverrideParser
import com.nexus.client.core.routing.RouteAction
import com.nexus.client.core.routing.RoutingMatcher
import com.nexus.client.core.routing.RoutingProfileParser
import com.nexus.client.core.routing.RoutePlanner
import com.nexus.client.core.security.SecretMasker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CoreTest {
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val publicKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    private fun config(extra: String = "") = """
        [Interface]
        PrivateKey = $key
        Address = 10.0.0.2/32
        $extra
        [Peer]
        PublicKey = $publicKey
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example:51820
    """.trimIndent()

    @Test fun detectsAwgGenerations() {
        assertEquals(AwgGeneration.AWG_21, AwgConfigParser.parse(config()).generation)
        assertEquals(AwgGeneration.AWG_30, AwgConfigParser.parse(config("RekeyAfterTime = 60")).generation)
        assertEquals(AwgGeneration.AWG_31, AwgConfigParser.parse(config("RandomTrailers = true")).generation)
    }

    @Test fun windowsCompatibleHeaderProtectionAndBooleanFlags() {
        val protection = "HeaderProtectionKey = $key\nS1 = 32\nS2 = 64\nS3 = 16\nS4 = 16"
        assertEquals(AwgGeneration.AWG_30, AwgConfigParser.parse(config(protection)).generation)
        for (flag in listOf("true", "false", "on", "off", "yes", "no", "1", "0")) {
            assertEquals(AwgGeneration.AWG_31, AwgConfigParser.parse(config("$protection\nRandomTrailers = $flag\nDisableCookies = $flag")).generation)
        }
        assertTrue(runCatching { AwgConfigParser.parse(config(protection.replace("S4 = 16", "S4 = 8"))) }.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCommandHooks() { AwgConfigParser.parse(config("PostUp = calc.exe")) }

    @Test fun masksSecretsAndEndpoint() {
        val masked = SecretMasker.mask(config() + "\nPresharedKey = $key\ntoken=secret")
        assertFalse(masked.contains(key))
        assertFalse(masked.contains("vpn.example"))
        assertFalse(masked.contains("token=secret"))
    }

    private val routingJson = """{
      "Name":"Test","GlobalProxy":true,"RouteOrder":"block-proxy-direct",
      "DirectSites":["domain:example.ru"],"DirectIp":["10.0.0.0/8"],
      "ProxySites":["domain:example.com"],"ProxyIp":["1.1.1.0/24"],
      "BlockSites":["full:ads.example"],"BlockIp":["203.0.113.0/24"]
    }"""

    @Test fun parsesAndClassifiesRouting() {
        val matcher = RoutingMatcher(RoutingProfileParser.parse(routingJson))
        assertEquals(RouteAction.DIRECT, matcher.domain("sub.example.ru"))
        assertEquals(RouteAction.PROXY, matcher.domain("government.ru"))
        assertEquals(RouteAction.PROXY, matcher.domain("site.рф"))
        assertEquals(RouteAction.PROXY, matcher.domain("api.gov.ru"))
        assertEquals(RouteAction.PROXY, matcher.domain("example.com"))
        assertEquals(RouteAction.BLOCK, matcher.domain("ads.example"))
        assertEquals(RouteAction.BLOCK, matcher.ip("203.0.113.5"))
    }

    @Test fun localOverridesWin() {
        val overrides = OverrideParser.parse("direct full:example.com\nblock 10.1.0.0/16")
        val matcher = RoutingMatcher(RoutingProfileParser.parse(routingJson), overrides)
        assertEquals(RouteAction.DIRECT, matcher.domain("example.com"))
        assertEquals(RouteAction.BLOCK, matcher.ip("10.1.2.3"))
    }

    @Test fun plansRealSplitIpRoutes() {
        val planned = RoutePlanner.apply(config(), RoutingProfileParser.parse(routingJson))
        assertFalse(planned.contains("AllowedIPs = 0.0.0.0/0"))
        assertTrue(planned.contains("AllowedIPs ="))
        assertFalse(planned.lineSequence().first { it.startsWith("AllowedIPs") }.contains("10.0.0.0/8"))
    }

    @Test fun cacheKeepsLastGoodData() {
        val dir = Files.createTempDirectory("nexus-cache").toFile()
        val cache = ValidatedCache(dir.resolve("routing.json"), 100)
        cache.replace("good".toByteArray()) { require(String(it) == "good") }
        runCatching { cache.replace("broken".toByteArray()) { require(String(it) == "good") } }
        assertEquals("good", String(cache.read { require(String(it) == "good") }!!))
        assertNull(cache.read { error("повреждён") })
        assertTrue(dir.resolve("routing.json").isFile)
        cache.replace("new-good".toByteArray()) { require(String(it) == "new-good") }
        assertEquals("new-good", String(cache.read { require(String(it) == "new-good") }!!))
        assertFalse(dir.resolve("routing.json.new").exists())
    }

    @Test fun hostnamesAreNotParsedAsLiteralIps() {
        assertNull(com.nexus.client.core.awg.IpPrefix.parse("localhost"))
        assertNull(com.nexus.client.core.awg.IpPrefix.parse("example.com/24"))
        assertNull(com.nexus.client.core.awg.IpPrefix.parse("999.0.0.1"))
        assertTrue(com.nexus.client.core.awg.IpPrefix.parse("2001:db8::/32") != null)
    }
    @Test fun secretMaskingIncludesNativeErrorsAndAuthHeaders() {
        val text = SecretMasker.mask("native key $key\nHeaderProtectionKey=$key\nAuthorization: Bearer private-token\nfailed endpoint vpn.example:54321")
        assertFalse(text.contains(key)); assertFalse(text.contains("private-token")); assertFalse(text.contains("vpn.example"))
    }
    @Test fun happAndCorruptProfiles() {
        val encoded = java.util.Base64.getEncoder().encodeToString(routingJson.toByteArray())
        assertEquals("Test", RoutingProfileParser.parse("happ://routing/onadd/$encoded").name)
        assertTrue(runCatching { RoutingProfileParser.parse("{broken") }.isFailure)
        assertTrue(runCatching { RoutingProfileParser.parse("") }.isFailure)
    }

    @Test fun parsesUniversalRoutingFormatsAndNormalizesOverrides() {
        val minimal = RoutingProfileParser.parse("""{"directSites":["domain:ru"],"blockIp":["203.0.113.10"]}""")
        assertEquals("Custom", minimal.name)
        assertTrue(minimal.globalProxy)
        assertEquals(listOf("domain:ru"), minimal.directSites)
        assertEquals(listOf("203.0.113.10/32"), minimal.blockIp)
        assertEquals("8.8.8.8", minimal.remoteDnsIp)
        assertEquals("1.1.1.1", minimal.domesticDnsIp)

        val encoded = java.util.Base64.getEncoder().encodeToString(routingJson.toByteArray())
        assertEquals("Test", RoutingProfileParser.parse(encoded).name)
        val html = """<html><body><a href="happ://routing/onadd/$encoded">Add</a></body></html>"""
        assertEquals("Test", RoutingProfileParser.parse(html).name)

        val plain = """
            # comment
            direct geosite:category-ru
            proxy youtube.com
            block 203.0.113.5
            dns-proxy https://dns.google/dns-query
        """.trimIndent()
        val parsedPlain = RoutingProfileParser.parse(plain)
        assertEquals("Custom", parsedPlain.name)
        assertEquals(listOf("geosite:category-ru"), parsedPlain.directSites)
        assertEquals(listOf("domain:youtube.com"), parsedPlain.proxySites)
        assertEquals(listOf("203.0.113.5/32"), parsedPlain.blockIp)
        assertEquals("8.8.8.8", parsedPlain.remoteDnsIp)

        assertEquals("domain:apple.com", OverrideParser.normalizeRuleTarget("https://apple.com/store"))
        assertEquals("geosite:apple", OverrideParser.normalizeRuleTarget("geosite:apple"))
        assertEquals("geoip:private", OverrideParser.normalizeRuleTarget("geoip:private"))
        assertEquals("192.0.2.1/32", OverrideParser.normalizeRuleTarget("192.0.2.1"))

        val updatedOverrides = OverrideParser.assignRule(
            "direct domain:apple.com\ndns-proxy-fallback https://common.dot.dns.yandex.net/dns-query 77.88.8.8",
            "apple.com",
            RouteAction.PROXY
        )
        assertTrue(updatedOverrides.contains("proxy domain:apple.com"))
        assertFalse(updatedOverrides.contains("direct domain:apple.com"))
        assertTrue(updatedOverrides.contains("dns-proxy-fallback"))
    }
}

