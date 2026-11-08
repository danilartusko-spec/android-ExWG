package com.nexus.client.data

import android.content.Context
import android.util.AtomicFile
import com.nexus.client.BuildConfig
import java.io.File
import java.util.UUID

object InstallationState {
    fun describe(context: Context, profiles: Int): String {
        val prefs = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
        val marker = AtomicFile(File(context.noBackupFilesDir, "installation-id"))
        val existing = runCatching { marker.readFully().toString(Charsets.UTF_8) }.getOrNull()
        val expected = prefs.getString("id", null)
        val id = existing ?: UUID.randomUUID().toString().also { value ->
            val output = marker.startWrite()
            try { output.write(value.toByteArray()); marker.finishWrite(output) }
            catch (error: Throwable) { marker.failWrite(output); throw error }
        }
        val restored = expected != null && expected != id
        if (restored) prefs.edit().putBoolean("restoreSuspected", true).apply()
        prefs.edit().putString("id", id).apply()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val state = when {
            prefs.getBoolean("restoreSuspected", false) -> "Различие маркеров: возможно восстановление или перенос данных; это не доказательство причины сетевого сбоя"
            existing == null && profiles > 0 -> "Первый запуск версии с маркером; профили уже существовали (обновление или перенос — причина не установлена)"
            else -> "Маркер установки согласован"
        }
        return "${context.packageName} · ${BuildConfig.VERSION_NAME}\ninstallation=$id\nfirstInstall=${info.firstInstallTime} lastUpdate=${info.lastUpdateTime}\nПрофилей: $profiles\n$state"
    }
}
