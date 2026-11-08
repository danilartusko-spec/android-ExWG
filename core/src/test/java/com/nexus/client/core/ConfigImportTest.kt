package com.nexus.client.core

import com.nexus.client.core.awg.ConfigImport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConfigImportTest {
    private val config = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.0.0.2/32
        [Peer]
        PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
        Endpoint = vpn.example:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive -> files.forEach { (name, data) ->
            archive.putNextEntry(ZipEntry(name)); archive.write(data.toByteArray()); archive.closeEntry()
        } }
        return out.toByteArray()
    }

    @Test fun importsPlainAndQrWithoutChangesToSecrets() {
        assertEquals(config, ConfigImport.fromQr(config).config)
        val file = ConfigImport.fromFile(config.byteInputStream(), "My server.conf").single()
        assertEquals("My server", file.name)
        assertEquals(config, file.config)
    }

    @Test fun importsNestedZipWithoutExtractingPaths() {
        val bytes = zip("../outside.conf" to config, "servers/Second.CONF" to config, "readme.txt" to "notes")
        val result = ConfigImport.fromFile(bytes.inputStream(), "profiles.zip")
        assertEquals(listOf("outside", "Second"), result.map { it.name })
    }

    @Test fun rejectsLinkQrAndCorruptZip() {
        assertTrue(runCatching { ConfigImport.fromQr("https://example.com/private") }.isFailure)
        assertTrue(runCatching { ConfigImport.fromFile("broken".byteInputStream(), "broken.zip") }.isFailure)
        assertTrue(runCatching { ConfigImport.fromFile(zip("readme.txt" to "nothing").inputStream(), "empty.zip") }.isFailure)
    }

    @Test fun rejectsEntireArchiveIfAnyConfigInvalid() {
        assertTrue(runCatching { ConfigImport.fromFile(zip("one.conf" to config, "bad.conf" to "not a config").inputStream(), "profiles.zip") }.isFailure)
    }

    @Test fun boundsZipBombsIncludingIgnoredFiles() {
        val bomb = zip("ignored.bin" to "x".repeat(ConfigImport.MAX_EXPANDED_BYTES + 1), "good.conf" to config)
        assertTrue(runCatching { ConfigImport.fromFile(bomb.inputStream(), "bomb.zip") }.isFailure)
        val oversized = zip("huge.conf" to "x".repeat(ConfigImport.MAX_CONFIG_BYTES + 1))
        assertTrue(runCatching { ConfigImport.fromFile(oversized.inputStream(), "large.zip") }.isFailure)
    }

    @Test fun limitsProfileCount() {
        val files = (0..ConfigImport.MAX_PROFILES).map { "$it.conf" to config }.toTypedArray()
        assertTrue(runCatching { ConfigImport.fromFile(zip(*files).inputStream(), "too-many.zip") }.isFailure)
    }
}
