package com.nexus.client.core.routing

import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.CancellationException

object RoutingProfileSourceFallback {
    private const val OFFICIAL_SOURCE = "https://raw.githubusercontent.com/hydraponique/roscomvpn-routing/main/HAPP/DEFAULT.DEEPLINK"

    /** The callback must download and validate each profile before returning it. */
    fun <T> loadProfile(source: String, loadValidatedProfile: (String) -> T): T {
        try {
            return loadValidatedProfile(source)
        } catch (primaryError: Exception) {
            if (primaryError is CancellationException || primaryError is InterruptedException) throw primaryError
            if (!isStandardSource(source)) throw primaryError
            try {
                return loadValidatedProfile(OFFICIAL_SOURCE)
            } catch (fallbackError: Exception) {
                if (fallbackError is CancellationException || fallbackError is InterruptedException) throw fallbackError
                if (fallbackError !== primaryError) fallbackError.addSuppressed(primaryError)
                throw fallbackError
            }
        }
    }

    private fun isStandardSource(source: String): Boolean {
        val uri = try { URI(source.trim()) } catch (_: URISyntaxException) { return false }
        return uri.scheme.equals("https", true) &&
            (uri.rawAuthority.equals("routing.help", true) || uri.rawAuthority.equals("routing.help:443", true)) &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }
}
