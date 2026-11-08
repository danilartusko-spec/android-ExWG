package com.nexus.client.core.cache

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ValidatedCache(private val file: File, private val maxBytes: Int = 2 * 1024 * 1024) {
    fun read(validator: (ByteArray) -> Unit): ByteArray? {
        val backup = backupFile()
        val source = when {
            file.isFile -> file
            backup.isFile -> {
                // Recover the last-good value if the process stopped during a
                // filesystem fallback that cannot replace files atomically.
                backup.renameTo(file)
                if (file.isFile) file else backup
            }
            else -> return null
        }
        if (source.length() > maxBytes) return null
        return runCatching { source.readBytes().also(validator) }.getOrNull()
    }

    @Synchronized
    fun replace(data: ByteArray, validator: (ByteArray) -> Unit) {
        require(data.size <= maxBytes) { "Ответ превышает разрешённый размер" }
        validator(data)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.new")
        try {
            FileOutputStream(temp).use { output ->
                output.write(data)
                output.fd.sync()
            }
            // Same-directory rename is atomic on Android's filesystem and is
            // available on API 21. Never delete the last-good file first.
            if (!temp.renameTo(file)) replaceWithBackupFallback(temp)
        } finally {
            temp.delete()
        }
    }

    private fun backupFile(): File = File(file.parentFile, "${file.name}.old")

    private fun replaceWithBackupFallback(temp: File) {
        val backup = backupFile()
        if (backup.exists() && file.exists() && !backup.delete()) {
            throw IOException("Cannot remove stale cache backup: $backup")
        }
        if (file.exists() && !file.renameTo(backup)) {
            throw IOException("Cannot preserve validated cache: $file -> $backup")
        }
        if (temp.renameTo(file)) {
            backup.delete()
            return
        }
        if (backup.exists()) backup.renameTo(file)
        throw IOException("Cannot rename validated cache: $temp -> $file")
    }
}
