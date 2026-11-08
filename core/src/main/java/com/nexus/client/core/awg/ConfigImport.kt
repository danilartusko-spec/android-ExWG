package com.nexus.client.core.awg

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

data class ImportedConfig(val name: String, val config: String, val generation: AwgGeneration)

/** Reads configs in memory. ZIP paths are never used as filesystem destinations. */
object ConfigImport {
    const val MAX_CONFIG_BYTES = 256 * 1024
    const val MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 2 * 1024 * 1024
    const val MAX_PROFILES = 20

    fun fromQr(text: String): ImportedConfig {
        require(text.trimStart().startsWith("[Interface]", ignoreCase = true)) {
            "QR-код должен содержать AWG-конфигурацию, а не ссылку. Используйте QR из AmneziaWG или файл .conf/.zip."
        }
        return checked("Ex WG", text)
    }

    fun fromFile(input: InputStream, fileName: String): List<ImportedConfig> {
        val bytes = readBounded(input, MAX_ARCHIVE_BYTES)
        val zip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            (bytes[2] == 3.toByte() || bytes[2] == 5.toByte() || bytes[2] == 7.toByte())
        if (!zip) {
            require(!fileName.endsWith(".zip", ignoreCase = true)) { "ZIP-архив повреждён или не поддерживается" }
            require(bytes.size <= MAX_CONFIG_BYTES) { "Конфигурация превышает 256 КБ" }
            return listOf(checked(safeName(fileName), decode(bytes)))
        }
        val result = mutableListOf<ImportedConfig>()
        var expanded = 0
        var entries = 0
        ZipInputStream(bytes.inputStream()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                require(++entries <= 128) { "Слишком много файлов в ZIP" }
                val isConfig = !entry.isDirectory && entry.name.endsWith(".conf", ignoreCase = true) && !entry.name.startsWith("__MACOSX/")
                require(entry.size <= MAX_EXPANDED_BYTES || entry.size < 0) { "ZIP содержит слишком большой файл" }
                val output = if (isConfig) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                while (true) {
                    val n = archive.read(buffer)
                    if (n < 0) break
                    expanded += n
                    require(expanded <= MAX_EXPANDED_BYTES) { "Распакованный ZIP превышает 2 МБ" }
                    if (output != null) {
                        require(output.size() + n <= MAX_CONFIG_BYTES) { "Конфигурация в ZIP превышает 256 КБ" }
                        output.write(buffer, 0, n)
                    }
                }
                if (output != null) {
                    require(result.size < MAX_PROFILES) { "В одном ZIP допускается до 20 профилей" }
                    result += checked(safeName(entry.name), decode(output.toByteArray()))
                }
                archive.closeEntry()
            }
        }
        require(result.isNotEmpty()) { "В ZIP не найдены файлы .conf" }
        return result
    }

    private fun checked(name: String, text: String): ImportedConfig {
        val parsed = AwgConfigParser.parse(text)
        return ImportedConfig(name, parsed.raw, parsed.generation)
    }

    private fun safeName(path: String) = path.replace('\\', '/').substringAfterLast('/')
        .substringBeforeLast('.', path.substringAfterLast('/')).filterNot(Char::isISOControl)
        .trim().take(80).ifBlank { "Ex WG" }

    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size() + n <= max) { "Файл превышает 4 МБ" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
