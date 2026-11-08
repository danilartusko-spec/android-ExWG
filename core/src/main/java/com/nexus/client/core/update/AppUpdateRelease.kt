package com.nexus.client.core.update

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class AppUpdateInfo(
    val available: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseNotes: String,
    val assetName: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val sha256: String,
    val publishedAt: String,
    val sizeBytes: Long = 0L,
)

object AppUpdateParser {
    const val DEFAULT_REPO = "euloxe-dev/android-ExWG"
    const val DEFAULT_RELEASE_API = "https://api.github.com/repos/$DEFAULT_REPO/releases/latest"
    const val MAX_APK_BYTES = 180L * 1024L * 1024L

    private val SHA256_EXACT_REGEX = Regex("^[0-9a-fA-F]{64}$")
    private val SHA256_WORD_REGEX = Regex("\\b([0-9a-fA-F]{64})\\b")

    fun normalizeVersion(version: String): String {
        return version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
    }

    fun compareVersions(a: String, b: String): Int {
        val cleanA = normalizeVersion(a)
        val cleanB = normalizeVersion(b)
        val partsA = if (cleanA.isEmpty()) emptyList() else cleanA.split('.').map { it.trim().toLongOrNull() ?: 0L }
        val partsB = if (cleanB.isEmpty()) emptyList() else cleanB.split('.').map { it.trim().toLongOrNull() ?: 0L }
        val maxSegments = maxOf(3, partsA.size, partsB.size)
        for (i in 0 until maxSegments) {
            val segA = partsA.getOrElse(i) { 0L }
            val segB = partsB.getOrElse(i) { 0L }
            if (segA < segB) return -1
            if (segA > segB) return 1
        }
        return 0
    }

    fun isTrustedDownloadUrl(url: String, allowLocalhostForTests: Boolean = false): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val uri = URI(trimmed)
            if (uri.userInfo != null || uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) {
                return@runCatching false
            }
            val rawHost = uri.host
            if (rawHost.isNullOrBlank()) return@runCatching false
            val host = rawHost.lowercase(Locale.US)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching false

            if (allowLocalhostForTests && (host == "127.0.0.1" || host == "localhost") && (scheme == "http" || scheme == "https")) {
                return@runCatching true
            }

            scheme == "https" && (
                host == "github.com" ||
                    host == "api.github.com" ||
                    host == "objects.githubusercontent.com" ||
                    host == "releases.githubusercontent.com" ||
                    host.endsWith(".githubusercontent.com")
                )
        }.getOrDefault(false)
    }

    fun parseReleaseJson(
        rawJson: String,
        currentVersion: String,
        supportedAbis: List<String>,
        allowLocalhostForTests: Boolean = false,
    ): AppUpdateInfo {
        val root = JSONObject(rawJson)
        val tagName = root.optCleanString("tag_name")
        val latestVersion = normalizeVersion(tagName)
        val cleanCurrent = normalizeVersion(currentVersion)
        val releaseNotes = root.optCleanString("body")
        val releasePageUrl = root.optCleanString("html_url")
        val publishedAt = root.optCleanString("published_at")
        val draft = root.optBoolean("draft", false)
        val prerelease = root.optBoolean("prerelease", false)

        if (draft || prerelease || latestVersion.isEmpty()) {
            return AppUpdateInfo(
                available = false,
                currentVersion = cleanCurrent,
                latestVersion = latestVersion,
                releaseNotes = releaseNotes,
                assetName = "",
                downloadUrl = "",
                releasePageUrl = releasePageUrl,
                sha256 = "",
                publishedAt = publishedAt,
                sizeBytes = 0L,
            )
        }

        val assetsArray = root.optJSONArray("assets") ?: JSONArray()
        val candidates = buildList {
            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.optJSONObject(i) ?: continue
                val name = asset.optCleanString("name")
                val browserUrl = asset.optCleanString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && isTrustedDownloadUrl(browserUrl, allowLocalhostForTests)) {
                    add(asset)
                }
            }
        }

        val chosenAsset = selectBestApkAsset(candidates, supportedAbis)
            ?: return AppUpdateInfo(
                available = false,
                currentVersion = cleanCurrent,
                latestVersion = latestVersion,
                releaseNotes = releaseNotes,
                assetName = "",
                downloadUrl = "",
                releasePageUrl = releasePageUrl,
                sha256 = "",
                publishedAt = publishedAt,
                sizeBytes = 0L,
            )

        val assetName = chosenAsset.optCleanString("name")
        val downloadUrl = chosenAsset.optCleanString("browser_download_url")
        val sizeBytes = chosenAsset.optLong("size", 0L).coerceAtLeast(0L)
        val sha256 = extractSha256(
            chosenAsset = chosenAsset,
            assetName = assetName,
            releaseNotes = releaseNotes,
            candidateCount = candidates.size,
        )
        val available = compareVersions(cleanCurrent, latestVersion) < 0 && downloadUrl.isNotBlank()

        return AppUpdateInfo(
            available = available,
            currentVersion = cleanCurrent,
            latestVersion = latestVersion,
            releaseNotes = releaseNotes,
            assetName = assetName,
            downloadUrl = downloadUrl,
            releasePageUrl = releasePageUrl,
            sha256 = sha256,
            publishedAt = publishedAt,
            sizeBytes = sizeBytes,
        )
    }

    fun verifyApkHeaderAndSha256(
        firstFourBytes: ByteArray,
        actualSha256Hex: String,
        expectedSha256Hex: String,
    ) {
        val isZipHeader = firstFourBytes.size >= 4 &&
            firstFourBytes[0] == 0x50.toByte() &&
            firstFourBytes[1] == 0x4B.toByte() &&
            firstFourBytes[2] == 0x03.toByte() &&
            firstFourBytes[3] == 0x04.toByte()
        if (!isZipHeader) {
            throw IllegalArgumentException("Скачанный файл не является корректным пакетом APK")
        }

        if (expectedSha256Hex.isNotBlank()) {
            val actual = actualSha256Hex.trim().lowercase(Locale.US)
            val expected = expectedSha256Hex.trim().lowercase(Locale.US)
            if (!SHA256_EXACT_REGEX.matches(expected)) {
                throw SecurityException("Контрольная сумма SHA-256 APK не совпала")
            }
            val matches = MessageDigest.isEqual(
                actual.toByteArray(Charsets.US_ASCII),
                expected.toByteArray(Charsets.US_ASCII),
            )
            if (!matches) {
                throw SecurityException("Контрольная сумма SHA-256 APK не совпала")
            }
        }
    }

    private fun selectBestApkAsset(
        candidates: List<JSONObject>,
        supportedAbis: List<String>,
    ): JSONObject? {
        if (candidates.isEmpty()) return null
        for (abi in supportedAbis) {
            val cleanAbi = abi.trim()
            if (cleanAbi.isEmpty()) continue
            val match = candidates.firstOrNull { candidate ->
                candidate.optCleanString("name").contains(cleanAbi, ignoreCase = true)
            }
            if (match != null) return match
        }
        val universal = candidates.firstOrNull { candidate ->
            candidate.optCleanString("name").contains("universal", ignoreCase = true)
        }
        return universal ?: candidates.first()
    }

    private fun extractSha256(
        chosenAsset: JSONObject,
        assetName: String,
        releaseNotes: String,
        candidateCount: Int,
    ): String {
        val digest = chosenAsset.optCleanString("digest")
        if (digest.startsWith("sha256:", ignoreCase = true)) {
            val hexPart = digest.substring("sha256:".length).trim()
            if (SHA256_EXACT_REGEX.matches(hexPart)) {
                return hexPart.lowercase(Locale.US)
            }
        }

        if (releaseNotes.isNotBlank() && assetName.isNotBlank()) {
            val lines = releaseNotes.lines()
            for (i in lines.indices) {
                val line = lines[i]
                if (line.contains(assetName, ignoreCase = true)) {
                    val sameLineMatch = SHA256_WORD_REGEX.find(line)?.groupValues?.getOrNull(1)
                    if (sameLineMatch != null) {
                        return sameLineMatch.lowercase(Locale.US)
                    }
                    val nextLine = lines.getOrNull(i + 1)
                    if (nextLine != null) {
                        val nextLineMatch = SHA256_WORD_REGEX.find(nextLine)?.groupValues?.getOrNull(1)
                        if (nextLineMatch != null) {
                            return nextLineMatch.lowercase(Locale.US)
                        }
                    }
                }
            }
        }

        if (candidateCount == 1 && releaseNotes.isNotBlank()) {
            val allMatches = SHA256_WORD_REGEX.findAll(releaseNotes).map { it.groupValues[1] }.toList()
            if (allMatches.size == 1) {
                return allMatches.single().lowercase(Locale.US)
            }
        }

        return ""
    }

    private fun JSONObject.optCleanString(key: String): String {
        if (isNull(key)) return ""
        return optString(key, "").trim()
    }
}
