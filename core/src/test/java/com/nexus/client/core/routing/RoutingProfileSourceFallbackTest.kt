package com.nexus.client.core.routing

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutingProfileSourceFallbackTest {
    private val standardSource = "https://routing.help/"
    private val officialSource = "https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/main/HAPP/DEFAULT.DEEPLINK"
    private val primaryProfile = """{
        "Name":"Primary","GlobalProxy":true,"RouteOrder":"block-proxy-direct",
        "DirectSites":["domain:primary.example"],"DirectIp":[],
        "ProxySites":[],"ProxyIp":[],"BlockSites":[],"BlockIp":[]
    }"""
    private val officialProfile = """{
        "Name":"Official","GlobalProxy":true,"RouteOrder":"block-proxy-direct",
        "DirectSites":["domain:direct.example"],"DirectIp":["198.51.100.0/24"],
        "ProxySites":["domain:proxy.example"],"ProxyIp":[],
        "BlockSites":["full:blocked.example"],"BlockIp":["203.0.113.0/24"]
    }"""

    // Catch an unconditional fallback that replaces a valid selected profile.
    @Test fun successfulPrimaryKeepsItsRulesWithoutOfficialDownload() {
        val requested = mutableListOf<String>()
        val profile = RoutingProfileSourceFallback.loadProfile(standardSource) { source ->
            requested += source
            check(source == standardSource) { "Unexpected fallback" }
            RoutingProfileParser.parse(primaryProfile)
        }

        assertEquals(listOf(standardSource), requested)
        assertEquals("Primary", profile.name)
        assertEquals(RouteAction.DIRECT, RoutingMatcher(profile).domain("sub.primary.example"))
        assertEquals(RouteAction.PROXY, RoutingMatcher(profile).domain("direct.example"))
    }

    // Catch the missing official-source retry and a retry with the wrong URL.
    @Test fun failedPrimaryLoadsAndParsesOfficialRules() {
        val requested = mutableListOf<String>()
        val primaryError = IOException("HTTP 503 from primary")
        val profile = RoutingProfileSourceFallback.loadProfile(standardSource) { source ->
            requested += source
            when (source) {
                standardSource -> throw primaryError
                officialSource -> RoutingProfileParser.parse(officialProfile)
                else -> error("Unexpected source")
            }
        }

        assertEquals(listOf(standardSource, officialSource), requested)
        assertEquals("Official", profile.name)
        val matcher = RoutingMatcher(profile)
        assertEquals(RouteAction.DIRECT, matcher.domain("sub.direct.example"))
        assertEquals(RouteAction.PROXY, matcher.domain("proxy.example"))
        assertEquals(RouteAction.BLOCK, matcher.domain("blocked.example"))
        assertEquals(RouteAction.DIRECT, matcher.ip("198.51.100.8"))
        assertEquals(RouteAction.BLOCK, matcher.ip("203.0.113.8"))
    }

    // Catch premature success when downloading worked but profile validation failed.
    @Test fun invalidPrimaryProfileUsesOfficialFallback() {
        val requested = mutableListOf<String>()
        val profile = RoutingProfileSourceFallback.loadProfile(standardSource) { source ->
            requested += source
            RoutingProfileParser.parse(when (source) {
                standardSource -> "{\"GlobalProxy\":true,\"RouteOrder\":\"broken\"}"
                officialSource -> officialProfile
                else -> error("Unexpected source")
            })
        }

        assertEquals(listOf(standardSource, officialSource), requested)
        assertEquals(RouteAction.BLOCK, RoutingMatcher(profile).domain("blocked.example"))
    }

    // Catch URL rewriting before the primary attempt or rejecting the default HTTPS port.
    @Test fun standardRootVariantsKeepOriginalSourceAndUseOfficialFallback() {
        for (source in listOf("https://routing.help", "https://ROUTING.HELP/", "https://routing.help:443/", "  https://routing.help/  ")) {
            val requested = mutableListOf<String>()
            val profile = RoutingProfileSourceFallback.loadProfile(source) { current ->
                requested += current
                when (current) {
                    source -> throw IOException("Primary unavailable")
                    officialSource -> RoutingProfileParser.parse(officialProfile)
                    else -> error("Source was rewritten")
                }
            }

            assertEquals(source, listOf(source, officialSource), requested)
            assertEquals(RouteAction.DIRECT, RoutingMatcher(profile).domain("direct.example"))
        }
    }

    // Catch substitution of a custom source, even when it mentions routing.help.
    @Test fun customSourcesAreNeverReplacedAfterFailure() {
        val sources = listOf(
            "https://custom.example/",
            "https://www.routing.help/",
            "https://routing.help/custom",
            "https://routing.help//",
            "https://routing.help/?profile=custom",
            "https://routing.help/?",
            "https://routing.help/#custom",
            "https://user:password@routing.help/",
            "https://routing.help:8443/",
            "https://routing.help:/",
            "http://routing.help/",
            "https://routing.help./",
            "happ://routing/onadd/custom",
            "{\"Name\":\"Custom\"}",
            "not a URI",
        )
        for (source in sources) {
            val requested = mutableListOf<String>()
            val primaryError = IOException("Custom source unavailable")
            val thrown = assertThrows(source, IOException::class.java) {
                RoutingProfileSourceFallback.loadProfile<RoutingProfile>(source) { current ->
                    requested += current
                    if (current == source) throw primaryError
                    RoutingProfileParser.parse(officialProfile)
                }
            }

            assertSame(source, primaryError, thrown)
            assertEquals(source, listOf(source), requested)
        }
    }

    // Catch losing the primary download's existing direct/proxy error chain.
    @Test fun bothDownloadFailuresPreserveOriginalErrors() {
        val directError = IOException("Protected-direct TLS failure")
        val primaryError = IOException("Primary proxy failure").also { it.addSuppressed(directError) }
        val officialError = IOException("Official source HTTP 503")
        val requested = mutableListOf<String>()
        val thrown = assertThrows(IOException::class.java) {
            RoutingProfileSourceFallback.loadProfile<RoutingProfile>(standardSource) { source ->
                requested += source
                when (source) {
                    standardSource -> throw primaryError
                    officialSource -> throw officialError
                    else -> error("Unexpected source")
                }
            }
        }

        assertEquals(listOf(standardSource, officialSource), requested)
        assertSame(officialError, thrown)
        assertEquals(listOf(primaryError), thrown.suppressed.toList())
        assertEquals(listOf(directError), thrown.suppressed.single().suppressed.toList())
    }

    // Catch validation outside the retry boundary, which would lose the primary error.
    @Test fun invalidOfficialProfilePreservesPrimaryFailure() {
        val primaryError = IOException("Primary unavailable")
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            RoutingProfileSourceFallback.loadProfile(standardSource) { source ->
                when (source) {
                    standardSource -> throw primaryError
                    officialSource -> RoutingProfileParser.parse("{\"Name\":\"Invalid\",\"DirectIp\":[\"not-an-IP\"]}")
                    else -> error("Unexpected source")
                }
            }
        }

        assertEquals(listOf(primaryError), thrown.suppressed.toList())
    }

    // Catch self-suppression replacing a reused failure with IllegalArgumentException.
    @Test fun reusedDownloadErrorIsRethrownWithoutSelfSuppression() {
        val requested = mutableListOf<String>()
        val error = IOException("Both sources unavailable")
        val thrown = assertThrows(IOException::class.java) {
            RoutingProfileSourceFallback.loadProfile<RoutingProfile>(standardSource) { source ->
                requested += source
                throw error
            }
        }

        assertEquals(listOf(standardSource, officialSource), requested)
        assertSame(error, thrown)
        assertEquals(emptyList<Throwable>(), thrown.suppressed.toList())
    }

    // Catch broad exception handling that converts cancellation into network retry.
    @Test fun primaryCancellationPropagatesWithoutFallback() {
        val requested = mutableListOf<String>()
        val cancellation = CancellationException("Update cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            RoutingProfileSourceFallback.loadProfile<RoutingProfile>(standardSource) { source ->
                requested += source
                throw cancellation
            }
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(standardSource), requested)
    }

    @Test fun fallbackCancellationPropagatesWithoutAttachingDownloadErrors() {
        val cancellation = CancellationException("Update cancelled")
        val requested = mutableListOf<String>()
        val thrown = assertThrows(CancellationException::class.java) {
            RoutingProfileSourceFallback.loadProfile<RoutingProfile>(standardSource) { source ->
                requested += source
                if (source == standardSource) throw IOException("Primary unavailable")
                throw cancellation
            }
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(standardSource, officialSource), requested)
        assertEquals(emptyList<Throwable>(), thrown.suppressed.toList())
    }

    // Catch an interrupted thread being treated as an ordinary source failure.
    @Test fun primaryInterruptionPropagatesWithoutFallback() {
        val requested = mutableListOf<String>()
        val interruption = InterruptedException("Download interrupted")
        val thrown = assertThrows(InterruptedException::class.java) {
            RoutingProfileSourceFallback.loadProfile<RoutingProfile>(standardSource) { source ->
                requested += source
                throw interruption
            }
        }

        assertSame(interruption, thrown)
        assertEquals(listOf(standardSource), requested)
    }

    @Test fun fallbackInterruptionPropagatesWithoutAttachingDownloadErrors() {
        val interruption = InterruptedException("Download interrupted")
        val thrown = assertThrows(InterruptedException::class.java) {
            RoutingProfileSourceFallback.loadProfile<RoutingProfile>(standardSource) { source ->
                if (source == standardSource) throw IOException("Primary unavailable")
                throw interruption
            }
        }

        assertSame(interruption, thrown)
        assertEquals(emptyList<Throwable>(), thrown.suppressed.toList())
    }

    // Catch Throwable-level handling attempting network recovery from a runtime Error.
    @Test fun runtimeErrorsPropagateWithoutFallback() {
        val requested = mutableListOf<String>()
        val error = LinkageError("Native validation unavailable")
        val thrown = assertThrows(LinkageError::class.java) {
            RoutingProfileSourceFallback.loadProfile<RoutingProfile>(standardSource) { source ->
                requested += source
                throw error
            }
        }

        assertSame(error, thrown)
        assertEquals(listOf(standardSource), requested)
    }
}
