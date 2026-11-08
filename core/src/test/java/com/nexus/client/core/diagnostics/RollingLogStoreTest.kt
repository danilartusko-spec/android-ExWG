package com.nexus.client.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset

class RollingLogStoreTest {
    private fun epoch(value: String) = Instant.parse(value).toEpochMilli()

    @Test
    fun dailyCompressionRoundTripsExactUtf8Text() {
        val directory = Files.createTempDirectory("exwg-log").toFile()
        val store = RollingLogStore(directory, ZoneOffset.UTC)
        val first = "2026-09-10T23:59:00.000Z  errno=101\nstack line\n\n"
        val second = "2026-09-11T00:01:00.000Z  точная ошибка\n\n"
        store.append(first, epoch("2026-09-10T23:59:00Z"))
        store.append(second, epoch("2026-09-11T00:01:00Z"))

        assertTrue(directory.listFiles().orEmpty().any { it.name == "exwg-20260910-p000.log.gz" })
        assertFalse(directory.listFiles().orEmpty().any { it.name == "exwg-20260910-p000.log" })
        val output = ByteArrayOutputStream()
        store.writeHistory(output, nowMillis = epoch("2026-09-11T00:02:00Z"))
        assertEquals(first + second, output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun rotatesLargeSegmentsWithoutLosingRecords() {
        val directory = Files.createTempDirectory("exwg-log").toFile()
        val store = RollingLogStore(directory, ZoneOffset.UTC, maxSegmentBytes = 32)
        val records = (1..5).map { "2026-09-11T00:00:0$it.000Z  event-$it\n\n" }
        records.forEach { store.append(it, epoch("2026-09-11T00:00:10Z")) }

        val output = ByteArrayOutputStream()
        store.writeHistory(output, nowMillis = epoch("2026-09-11T00:01:00Z"))
        assertEquals(records.joinToString(""), output.toString(Charsets.UTF_8.name()))
        assertTrue(store.summary().files >= 5)
    }

    @Test
    fun removesCalendarDaysOutsideRetention() {
        val directory = Files.createTempDirectory("exwg-log").toFile()
        val store = RollingLogStore(directory, ZoneOffset.UTC, retentionDays = 2)
        store.append("old\n\n", epoch("2026-09-09T12:00:00Z"))
        store.append("previous\n\n", epoch("2026-09-10T12:00:00Z"))
        store.append("today\n\n", epoch("2026-09-11T12:00:00Z"))

        val output = ByteArrayOutputStream()
        store.writeHistory(output, nowMillis = epoch("2026-09-11T12:01:00Z"))
        assertEquals("previous\n\ntoday\n\n", output.toString(Charsets.UTF_8.name()))
        assertEquals(2, store.summary().days)
    }

    @Test
    fun purgesLegacyUnrecognizedLogFilesOnStartupAndMaintenance() {
        val directory = Files.createTempDirectory("exwg-log").toFile()
        val legacyFile = java.io.File(directory, "legacy-20260908-p000.log.gz").apply { writeText("legacy") }
        assertTrue(legacyFile.exists())

        val store = RollingLogStore(directory, ZoneOffset.UTC)
        assertFalse(legacyFile.exists())

        val strayFile = java.io.File(directory, "oldbrand-20260909-p000.log").apply { writeText("stray") }
        store.append("2026-09-11T12:00:00.000Z  ok\n\n", epoch("2026-09-11T12:00:00Z"))
        assertFalse(strayFile.exists())
        assertEquals(1, store.summary().files)
    }
}
