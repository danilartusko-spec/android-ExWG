package com.nexus.client.core.diagnostics

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class DiagnosticHistorySummary(
    val days: Int,
    val files: Int,
    val bytesOnDisk: Long,
    val lastStorageError: String? = null,
)

/**
 * Lossless, app-private diagnostic history. The open segment is plain UTF-8 so
 * an abrupt process death only risks the last write. Closed segments are gzip
 * compressed and can be exported back to the exact original text.
 */
class RollingLogStore(
    private val directory: File,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val retentionDays: Int = 14,
    private val maxBytesOnDisk: Long = 64L * 1024 * 1024,
    private val maxSegmentBytes: Long = 8L * 1024 * 1024,
) {
    @Volatile private var lastStorageError: String? = null

    init {
        require(retentionDays > 0)
        require(maxBytesOnDisk > 0)
        require(maxSegmentBytes > 0)
        check(directory.exists() || directory.mkdirs()) { "Cannot create diagnostics directory: $directory" }
        purgeUnrecognizedFiles()
    }

    @Synchronized
    fun append(record: String, nowMillis: Long = System.currentTimeMillis()) {
        lastStorageError = null
        val day = day(nowMillis)
        maintain(day)
        val bytes = record.toByteArray(Charsets.UTF_8)
        var target = currentSegment(day)
        if (target.exists() && target.length() > 0 && target.length() + bytes.size > maxSegmentBytes) {
            compress(target)
            target = nextSegment(day)
        }
        FileOutputStream(target, true).use { it.write(bytes) }
        target.setLastModified(nowMillis)
        if (target.length() >= maxSegmentBytes) compress(target)
        prune(day)
    }

    @Synchronized
    fun writeHistory(output: OutputStream, days: Int? = null, nowMillis: Long = System.currentTimeMillis()) {
        require(days == null || days > 0)
        val today = day(nowMillis)
        maintain(today)
        val firstDay = days?.let { today.minusDays((it - 1).toLong()) }
        historyFiles()
            .filter { file -> firstDay == null || fileDay(file)?.let { !it.isBefore(firstDay) } == true }
            .forEach { file ->
                if (file.name.endsWith(".gz")) {
                    GZIPInputStream(FileInputStream(file)).use { it.copyTo(output) }
                } else {
                    FileInputStream(file).use { it.copyTo(output) }
                }
            }
    }

    @Synchronized
    fun recentRecords(maxChars: Int = 512 * 1024): List<String> {
        val active = historyFiles().lastOrNull { it.name.endsWith(".log") } ?: return emptyList()
        val length = active.length()
        // UTF-8 uses up to four bytes per character. Start far enough back to
        // preserve a complete MAX_CHARS-sized UI window before record splitting.
        val start = (length - maxChars * 4L).coerceAtLeast(0L)
        val bytes = FileInputStream(active).use { input ->
            if (start > 0) input.skip(start)
            input.readBytes()
        }
        var text = bytes.toString(Charsets.UTF_8)
        if (start > 0) text = text.substringAfter("\n\n", "")
        return text.trimEnd().split(Regex("\\n\\n(?=\\d{4}-\\d{2}-\\d{2}T)"))
            .filter(String::isNotBlank)
            .takeLast(1000)
    }

    @Synchronized
    fun summary(): DiagnosticHistorySummary {
        val files = historyFiles()
        return DiagnosticHistorySummary(
            days = files.mapNotNull(::fileDay).distinct().size,
            files = files.size,
            bytesOnDisk = files.sumOf(File::length),
            lastStorageError = lastStorageError,
        )
    }

    private fun maintain(today: LocalDate) {
        purgeUnrecognizedFiles()
        reconcileInterruptedCompression()
        historyFiles().filter { file ->
            file.name.endsWith(".log") && (fileDay(file) != today || file.length() >= maxSegmentBytes)
        }.forEach(::compress)
        prune(today)
    }

    private fun purgeUnrecognizedFiles() {
        directory.listFiles().orEmpty()
            .filter { it.isFile && !FILE_PATTERN.matches(it.name) && !it.name.endsWith(".gz.tmp") }
            .forEach { runCatching { it.delete() } }
    }

    private fun reconcileInterruptedCompression() {
        directory.listFiles().orEmpty()
            .filter { FILE_PATTERN.matches(it.name) }
            .groupBy { it.name.removeSuffix(".gz") }
            .values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                val plain = duplicates.firstOrNull { it.name.endsWith(".log") } ?: return@forEach
                val compressed = duplicates.firstOrNull { it.name.endsWith(".gz") } ?: return@forEach
                if (gzipEqualsPlain(compressed, plain)) plain.delete() else compressed.delete()
            }
    }

    private fun gzipEqualsPlain(compressed: File, plain: File): Boolean = runCatching {
        GZIPInputStream(FileInputStream(compressed)).use { zipped ->
            FileInputStream(plain).use { original ->
                val zippedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val plainBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val zippedCount = zipped.read(zippedBuffer)
                    val plainCount = original.read(plainBuffer)
                    if (zippedCount != plainCount) return@runCatching false
                    if (zippedCount < 0) return@runCatching true
                    if (!zippedBuffer.copyOf(zippedCount).contentEquals(plainBuffer.copyOf(plainCount))) return@runCatching false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }
    }.getOrDefault(false)

    private fun prune(today: LocalDate) {
        val oldestAllowed = today.minusDays((retentionDays - 1).toLong())
        historyFiles().filter { fileDay(it)?.isBefore(oldestAllowed) == true }.forEach(File::delete)
        var files = historyFiles()
        var total = files.sumOf(File::length)
        for (file in files.filter { it.name.endsWith(".gz") }) {
            if (total <= maxBytesOnDisk) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun currentSegment(day: LocalDate): File {
        val active = historyFiles().lastOrNull { fileDay(it) == day && it.name.endsWith(".log") }
        return active ?: nextSegment(day)
    }

    private fun nextSegment(day: LocalDate): File {
        val highest = historyFiles().filter { fileDay(it) == day }.maxOfOrNull(::filePart) ?: -1
        return File(directory, "exwg-${DAY_FORMAT.format(day)}-p${(highest + 1).toString().padStart(3, '0')}.log")
    }

    private fun compress(source: File) {
        if (!source.isFile || !source.name.endsWith(".log")) return
        val destination = File(source.parentFile, source.name + ".gz")
        val temporary = File(source.parentFile, source.name + ".gz.tmp")
        temporary.delete()
        try {
            FileInputStream(source).use { input ->
                GZIPOutputStream(FileOutputStream(temporary)).use { output -> input.copyTo(output) }
            }
            if (destination.exists() && !destination.delete()) error("Cannot replace $destination")
            check(temporary.renameTo(destination)) { "Cannot publish $destination" }
            destination.setLastModified(source.lastModified())
            check(source.delete()) { "Cannot remove compressed source $source" }
        } catch (error: Throwable) {
            temporary.delete()
            if (source.exists()) destination.delete()
            lastStorageError = "${error.javaClass.name}: ${error.message}"
            // Keep the original plain segment intact and retry during the next maintenance pass.
        }
    }

    private fun historyFiles(): List<File> = directory.listFiles().orEmpty()
        .filter { FILE_PATTERN.matches(it.name) }
        .groupBy { it.name.removeSuffix(".gz") }
        .values
        .map { duplicates -> duplicates.firstOrNull { it.name.endsWith(".gz") } ?: duplicates.first() }
        .sortedWith(compareBy<File>({ fileDay(it) }, { filePart(it) }))

    private fun fileDay(file: File): LocalDate? = FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1)
        ?.let { runCatching { LocalDate.parse(it, DAY_FORMAT) }.getOrNull() }

    private fun filePart(file: File): Int = FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(2)?.toIntOrNull() ?: -1
    private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()

    companion object {
        private val DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
        private val FILE_PATTERN = Regex("exwg-(\\d{8})-p(\\d{3,})\\.log(?:\\.gz)?")
    }
}
