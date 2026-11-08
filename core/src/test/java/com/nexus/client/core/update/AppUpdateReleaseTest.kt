package com.nexus.client.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppUpdateReleaseTest {

    private val validApkMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    private val sampleHashA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val sampleHashB = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun normalizeAndCompareVersionsHandlePrefixesSuffixesAndOrdering() {
        assertEquals("0.9.8", AppUpdateParser.normalizeVersion(" v0.9.8-rc1+build42 "))
        assertEquals("1.2.0", AppUpdateParser.normalizeVersion("V1.2.0"))

        assertEquals(-1, AppUpdateParser.compareVersions("0.9.7", "0.9.8"))
        assertEquals(0, AppUpdateParser.compareVersions("v0.9.8", "0.9.8"))
        assertEquals(-1, AppUpdateParser.compareVersions("0.9.8", "0.10.0"))
        assertEquals(1, AppUpdateParser.compareVersions("1.0.0", "0.9.9"))
        assertEquals(0, AppUpdateParser.compareVersions("0.9.8-rc1", "0.9.8"))
        assertEquals(0, AppUpdateParser.compareVersions("1.2", "1.2.0"))

        // Algebraic properties: idempotence, reflexivity, antisymmetry, transitivity
        val versions = listOf(
            "0.0.1",
            "0.9.7",
            "v0.9.8-rc1",
            "0.9.8",
            "0.10.0",
            "1.0.0+2026",
            "1.2",
            "1.2.0",
            "2.0.0",
        )
        for (v in versions) {
            val once = AppUpdateParser.normalizeVersion(v)
            val twice = AppUpdateParser.normalizeVersion(once)
            assertEquals("normalizeVersion must be idempotent for $v", once, twice)
            assertEquals("compareVersions must be reflexive for $v", 0, AppUpdateParser.compareVersions(v, v))
        }
        for (a in versions) {
            for (b in versions) {
                val ab = AppUpdateParser.compareVersions(a, b)
                val ba = AppUpdateParser.compareVersions(b, a)
                assertEquals("compareVersions must be antisymmetric for ($a, $b)", ab, -ba)
                for (c in versions) {
                    val bc = AppUpdateParser.compareVersions(b, c)
                    val ac = AppUpdateParser.compareVersions(a, c)
                    if (ab < 0 && bc < 0) {
                        assertEquals("compareVersions must be transitive for ($a < $b < $c)", -1, ac)
                    }
                    if (ab == 0 && bc == 0) {
                        assertEquals("compareVersions must be transitive for ($a == $b == $c)", 0, ac)
                    }
                }
            }
        }
    }

    @Test
    fun parseReleaseJsonSelectsPreferredAbiOrFallsBackToUniversal() {
        val rawJson = """
            {
              "tag_name": "v0.9.8",
              "html_url": "https://github.com/euloxe-dev/android-ExWG/releases/tag/v0.9.8",
              "published_at": "2026-11-08T00:00:00Z",
              "draft": false,
              "prerelease": false,
              "body": "Release 0.9.8 notes",
              "assets": [
                {
                  "name": "ex-wg-0.9.8-universal.apk",
                  "size": 45000000,
                  "browser_download_url": "https://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/ex-wg-0.9.8-universal.apk"
                },
                {
                  "name": "ex-wg-0.9.8-armeabi-v7a.apk",
                  "size": 22000000,
                  "browser_download_url": "https://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/ex-wg-0.9.8-armeabi-v7a.apk"
                },
                {
                  "name": "ex-wg-0.9.8-arm64-v8a.apk",
                  "size": 25000000,
                  "browser_download_url": "https://objects.githubusercontent.com/github-production-release-asset/ex-wg-0.9.8-arm64-v8a.apk"
                }
              ]
            }
        """.trimIndent()

        val arm64Info = AppUpdateParser.parseReleaseJson(
            rawJson = rawJson,
            currentVersion = "0.9.7",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertTrue(arm64Info.available)
        assertEquals("0.9.7", arm64Info.currentVersion)
        assertEquals("0.9.8", arm64Info.latestVersion)
        assertEquals("ex-wg-0.9.8-arm64-v8a.apk", arm64Info.assetName)
        assertEquals(25000000L, arm64Info.sizeBytes)

        val fallbackUniversal = AppUpdateParser.parseReleaseJson(
            rawJson = rawJson,
            currentVersion = "0.9.7",
            supportedAbis = listOf("x86_64", "x86"),
        )
        assertTrue(fallbackUniversal.available)
        assertEquals("ex-wg-0.9.8-universal.apk", fallbackUniversal.assetName)
        assertEquals(45000000L, fallbackUniversal.sizeBytes)

        val alreadyUpToDate = AppUpdateParser.parseReleaseJson(
            rawJson = rawJson,
            currentVersion = "0.9.8",
            supportedAbis = listOf("arm64-v8a"),
        )
        assertFalse(alreadyUpToDate.available)
    }

    @Test
    fun parseReleaseJsonExtractsSha256FromDigestAndReleaseBody() {
        val digestJson = """
            {
              "tag_name": "v0.9.8",
              "html_url": "https://github.com/euloxe-dev/android-ExWG/releases/tag/v0.9.8",
              "published_at": "2026-11-08T00:00:00Z",
              "body": "Release notes without hash",
              "assets": [
                {
                  "name": "ex-wg-0.9.8-arm64-v8a.apk",
                  "size": 25000000,
                  "digest": "sha256:${sampleHashA.uppercase()}",
                  "browser_download_url": "https://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/ex-wg-0.9.8-arm64-v8a.apk"
                }
              ]
            }
        """.trimIndent()

        val fromDigest = AppUpdateParser.parseReleaseJson(
            rawJson = digestJson,
            currentVersion = "0.9.7",
            supportedAbis = listOf("arm64-v8a"),
        )
        assertEquals(sampleHashA, fromDigest.sha256)

        val bodyJson = """
            {
              "tag_name": "v0.9.8",
              "html_url": "https://github.com/euloxe-dev/android-ExWG/releases/tag/v0.9.8",
              "published_at": "2026-11-08T00:00:00Z",
              "body": "### Checksums\n- `ex-wg-0.9.8-armeabi-v7a.apk`: `$sampleHashA`\n- `ex-wg-0.9.8-arm64-v8a.apk`\n  `${sampleHashB.uppercase()}`",
              "assets": [
                {
                  "name": "ex-wg-0.9.8-armeabi-v7a.apk",
                  "browser_download_url": "https://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/ex-wg-0.9.8-armeabi-v7a.apk"
                },
                {
                  "name": "ex-wg-0.9.8-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/ex-wg-0.9.8-arm64-v8a.apk"
                }
              ]
            }
        """.trimIndent()

        val fromSameLine = AppUpdateParser.parseReleaseJson(
            rawJson = bodyJson,
            currentVersion = "0.9.7",
            supportedAbis = listOf("armeabi-v7a"),
        )
        assertEquals(sampleHashA, fromSameLine.sha256)

        val fromNextLine = AppUpdateParser.parseReleaseJson(
            rawJson = bodyJson,
            currentVersion = "0.9.7",
            supportedAbis = listOf("arm64-v8a"),
        )
        assertEquals(sampleHashB, fromNextLine.sha256)

        val singleAssetSingleHashJson = """
            {
              "tag_name": "v0.9.8",
              "html_url": "https://github.com/euloxe-dev/android-ExWG/releases/tag/v0.9.8",
              "published_at": "2026-11-08T00:00:00Z",
              "body": "SHA-256: $sampleHashA",
              "assets": [
                {
                  "name": "ex-wg-release.apk",
                  "browser_download_url": "https://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/ex-wg-release.apk"
                }
              ]
            }
        """.trimIndent()

        val fromSingleHash = AppUpdateParser.parseReleaseJson(
            rawJson = singleAssetSingleHashJson,
            currentVersion = "0.9.7",
            supportedAbis = listOf("arm64-v8a"),
        )
        assertEquals(sampleHashA, fromSingleHash.sha256)
    }

    @Test
    fun isTrustedDownloadUrlEnforcesHttpsGitHubHostsAndNoCredentials() {
        assertTrue(
            AppUpdateParser.isTrustedDownloadUrl(
                "https://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/app.apk",
            ),
        )
        assertTrue(
            AppUpdateParser.isTrustedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/12345/app.apk",
            ),
        )
        assertTrue(
            AppUpdateParser.isTrustedDownloadUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/app.apk",
            ),
        )

        assertFalse(
            AppUpdateParser.isTrustedDownloadUrl(
                "http://github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/app.apk",
            ),
        )
        assertFalse(
            AppUpdateParser.isTrustedDownloadUrl(
                "https://evil-github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/app.apk",
            ),
        )
        assertFalse(
            AppUpdateParser.isTrustedDownloadUrl(
                "https://githubusercontent.com.evil.example/app.apk",
            ),
        )
        assertFalse(
            AppUpdateParser.isTrustedDownloadUrl(
                "https://user:pass@github.com/euloxe-dev/android-ExWG/releases/download/v0.9.8/app.apk",
            ),
        )
        assertFalse(AppUpdateParser.isTrustedDownloadUrl("http://127.0.0.1:8080/app.apk", allowLocalhostForTests = false))
        assertTrue(AppUpdateParser.isTrustedDownloadUrl("http://127.0.0.1:8080/app.apk", allowLocalhostForTests = true))
    }

    @Test
    fun verifyApkHeaderAndSha256ValidatesZipMagicAndConstantTimeDigest() {
        // Valid APK magic + matching SHA-256 (case-insensitive)
        AppUpdateParser.verifyApkHeaderAndSha256(
            firstFourBytes = validApkMagic,
            actualSha256Hex = sampleHashA,
            expectedSha256Hex = sampleHashA.uppercase(),
        )

        // Valid APK magic + blank expected SHA-256 (allowed when release does not publish checksum)
        AppUpdateParser.verifyApkHeaderAndSha256(
            firstFourBytes = validApkMagic,
            actualSha256Hex = sampleHashA,
            expectedSha256Hex = "",
        )

        // Bad magic bytes (e.g., HTML error page "<!DO")
        try {
            AppUpdateParser.verifyApkHeaderAndSha256(
                firstFourBytes = "<!DO".toByteArray(Charsets.US_ASCII),
                actualSha256Hex = sampleHashA,
                expectedSha256Hex = sampleHashA,
            )
            fail("Expected IllegalArgumentException for non-ZIP header")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("APK"))
        }

        // Truncated header
        try {
            AppUpdateParser.verifyApkHeaderAndSha256(
                firstFourBytes = byteArrayOf(0x50, 0x4B),
                actualSha256Hex = sampleHashA,
                expectedSha256Hex = sampleHashA,
            )
            fail("Expected IllegalArgumentException for truncated header")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("APK"))
        }

        // Mismatched SHA-256
        try {
            AppUpdateParser.verifyApkHeaderAndSha256(
                firstFourBytes = validApkMagic,
                actualSha256Hex = sampleHashA,
                expectedSha256Hex = sampleHashB,
            )
            fail("Expected SecurityException for mismatched SHA-256")
        } catch (expected: SecurityException) {
            assertTrue(expected.message.orEmpty().contains("SHA-256"))
        }
    }
}
