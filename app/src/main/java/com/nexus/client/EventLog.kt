package com.nexus.client

import android.content.Context
import org.json.JSONArray
import com.nexus.client.core.security.SecretMasker
import com.nexus.client.core.diagnostics.DiagnosticHistorySummary
import com.nexus.client.core.diagnostics.RollingLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.util.Log

object EventLog {
    private const val MAX_ENTRIES = 1000
    private const val MAX_CHARS = 512 * 1024
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    val isEnabled: Boolean get() = mutableEnabled.value
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = mutableEntries.asStateFlow()
    private var appContext: Context? = null
    private var history: RollingLogStore? = null
    @Volatile private var lastStorageError: String? = null

    @Synchronized
    fun initialize(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        mutableEnabled.value = false
        mutableEntries.value = emptyList()
        runCatching {
            val diagDir = File(appCtx.noBackupFilesDir, "diagnostics")
            if (diagDir.isDirectory) {
                diagDir.listFiles().orEmpty()
                    .filter { it.isFile && !it.name.startsWith("exwg-") }
                    .forEach { it.delete() }
            }
            val legacyPrefs = appCtx.getSharedPreferences("debug_log_v2", Context.MODE_PRIVATE)
            if (legacyPrefs.contains("entries")) {
                legacyPrefs.edit().remove("entries").putBoolean("migrated_to_daily_files", true).apply()
            }
        }
    }

    @Synchronized
    private fun ensureHistoryInitialized() {
        val context = appContext ?: return
        if (history != null) return
        history = runCatching { RollingLogStore(File(context.noBackupFilesDir, "diagnostics")) }
            .onFailure {
                lastStorageError = "${it.javaClass.name}: ${it.message}"
                Log.e("ExWgDiagnostics", "Cannot initialize diagnostic history", it)
            }
            .getOrNull()
        val prefs = context.getSharedPreferences("debug_log_v2", Context.MODE_PRIVATE)
        val saved = prefs.getString("entries", "[]")
        val legacy = runCatching { val data = JSONArray(saved); List(data.length()) { data.getString(it) }.takeLast(MAX_ENTRIES) }.getOrDefault(emptyList())
        val archive = history
        if (legacy.isNotEmpty() && archive != null) {
            runCatching {
                archive.append(legacy.joinToString("\n\n", postfix = "\n\n"))
                check(prefs.edit().remove("entries").putBoolean("migrated_to_daily_files", true).commit()) {
                    "Cannot commit legacy diagnostic migration"
                }
            }.onFailure {
                lastStorageError = "${it.javaClass.name}: ${it.message}"
                Log.e("ExWgDiagnostics", "Cannot migrate legacy diagnostic history", it)
            }
        }
        mutableEntries.value = runCatching { history?.recentRecords(MAX_CHARS).orEmpty() }.getOrDefault(legacy)
    }

    @Synchronized
    fun setEnabled(enable: Boolean) {
        if (enable) {
            ensureHistoryInitialized()
            val wasEnabled = mutableEnabled.value
            mutableEnabled.value = true
            if (!wasEnabled) {
                add("Режим диагностики включён")
            }
        } else {
            mutableEnabled.value = false
        }
    }

    @Synchronized
    fun add(message: String) {
        if (!mutableEnabled.value) return
        ensureHistoryInitialized()
        // Keep original addresses/codes/stack traces. Never log a whole config.
        val scrubbed = SecretMasker.maskSecretsOnly(message)
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val record = "$timestamp  $scrubbed"
        history?.let { archive ->
            runCatching { archive.append("$record\n\n") }
                .onSuccess { lastStorageError = null }
                .onFailure {
                    lastStorageError = "${it.javaClass.name}: ${it.message}"
                    Log.e("ExWgDiagnostics", "Cannot append diagnostic history", it)
                }
        }
        val uiDetail = if (scrubbed.length <= 65536) scrubbed else scrubbed.take(65536) + "\n[UI preview truncated at 64 KiB; full entry is in daily history]"
        val list = (mutableEntries.value + "$timestamp  $uiDetail").takeLast(MAX_ENTRIES).toMutableList()
        var size = list.sumOf(String::length)
        while (size > MAX_CHARS && list.size > 1) size -= list.removeAt(0).length
        mutableEntries.value = list
    }

    fun error(stage: String, error: Throwable) = add("$stage\n${error.stackTraceToString()}")
    fun export(): String = entries.value.joinToString("\n\n")
    @Synchronized
    fun historySummary(): DiagnosticHistorySummary {
        if (mutableEnabled.value) ensureHistoryInitialized()
        val summary = history?.summary() ?: DiagnosticHistorySummary(0, 0, 0)
        return if (lastStorageError == null) summary else summary.copy(lastStorageError = lastStorageError)
    }
    @Synchronized
    fun writeHistory(output: OutputStream, days: Int? = null) {
        ensureHistoryInitialized()
        requireNotNull(history) { "EventLog is not initialized" }.writeHistory(output, days)
    }
}
