package com.nexus.client.data

import android.content.Context
import android.util.AtomicFile
import com.nexus.client.EventLog
import com.nexus.client.core.awg.AwgConfigParser
import com.nexus.client.core.awg.AwgGeneration
import com.nexus.client.core.awg.ImportedConfig
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

data class ProfileMeta(
    val id: String,
    val name: String,
    val generation: AwgGeneration,
    val routingUrl: String = "https://routing.help/",
    val overrides: String = "",
    val lastRoutingUpdate: Long = 0,
    val routingStatus: String = "Ещё не проверялся",
    val createdAt: Long = 0,
)

data class RoutingSettings(
    val source: String = "https://routing.help/",
    val overrides: String = "",
    val lastRoutingUpdate: Long = 0,
    val routingStatus: String = "Ещё не проверялся",
)

class ProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, "profiles").also(File::mkdirs)
    private val keys = ProfileKeys(appContext)

    init { migrateRoutingSettings() }

    private fun rawList(): List<ProfileMeta> = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull(::readMeta)

    fun list(): List<ProfileMeta> {
        val settings = routingSettings()
        return rawList()
            .map {
                it.copy(
                    routingUrl = settings.source,
                    overrides = settings.overrides,
                    lastRoutingUpdate = settings.lastRoutingUpdate,
                    routingStatus = settings.routingStatus,
                )
            }
            .sortedWith(compareByDescending<ProfileMeta> { it.createdAt }.thenBy { it.name.lowercase() })
    }

    fun activeId(): String? = prefs.getString("active", null)?.takeIf { id -> list().any { it.id == id } }
    fun active(): ProfileMeta? = activeId()?.let { id -> list().firstOrNull { it.id == id } }

    fun appLanguage(): String {
        val raw = prefs.getString(UI_LANGUAGE, "ru")?.trim()?.lowercase().orEmpty()
        return if (raw == "en") "en" else "ru"
    }

    fun saveAppLanguage(languageCode: String) {
        val normalized = if (languageCode.trim().equals("en", ignoreCase = true)) "en" else "ru"
        prefs.edit().putString(UI_LANGUAGE, normalized).apply()
    }

    fun select(id: String) {
        require(readMeta(id) != null) { "Профиль не найден" }
        prefs.edit().putString("active", id).apply()
        EventLog.add("Выбран профиль ${readMeta(id)?.name}")
    }

    /** Moves the potentially slow first Android Keystore initialization away from the import button. */
    fun prewarmCrypto() { keys.get() }

    /** Performs all expensive validation before the confirmation dialog is shown. */
    fun validateImports(candidates: List<ImportedConfig>): List<ImportedConfig> {
        require(candidates.isNotEmpty()) { "Нет конфигураций для импорта" }
        return candidates.map { candidate ->
            val parsed = AwgConfigParser.parse(candidate.config)
            org.amnezia.awg.config.Config.parse(parsed.raw.byteInputStream())
            candidate.copy(config = parsed.raw, generation = parsed.generation)
        }
    }

    fun routingSettings(): RoutingSettings {
        val fallbackMeta = rawList().maxByOrNull { it.lastRoutingUpdate }
        return RoutingSettings(
            source = prefs.getString(GLOBAL_SOURCE, "https://routing.help/").orEmpty().ifBlank { "https://routing.help/" },
            overrides = prefs.getString(GLOBAL_OVERRIDES, "").orEmpty(),
            lastRoutingUpdate = prefs.getLong(GLOBAL_LAST_UPDATE, fallbackMeta?.lastRoutingUpdate ?: 0L),
            routingStatus = prefs.getString(GLOBAL_STATUS, null)
                ?.takeIf(String::isNotBlank)
                ?: fallbackMeta?.routingStatus
                ?: "Ещё не проверялся",
        )
    }

    @Synchronized fun saveRoutingSettings(settings: RoutingSettings) {
        val normalized = settings.copy(
            source = settings.source.trim().ifBlank { "https://routing.help/" },
            overrides = settings.overrides.trim(),
        )
        val edit = prefs.edit().putString(GLOBAL_SOURCE, normalized.source).putString(GLOBAL_OVERRIDES, normalized.overrides)
        rawList().forEach { meta ->
            edit.putString("meta.${meta.id}", encodeMeta(meta.copy(routingUrl = normalized.source, overrides = normalized.overrides)))
        }
        check(edit.commit()) { "Не удалось сохранить общие настройки маршрутизации" }
        EventLog.add("Общие правила сохранены: profiles=${rawList().size}, overrideBytes=${normalized.overrides.toByteArray().size}")
    }

    @Synchronized fun updateRoutingState(lastUpdate: Long, status: String) {
        val edit = prefs.edit()
            .putLong(GLOBAL_LAST_UPDATE, lastUpdate)
            .putString(GLOBAL_STATUS, status)
        rawList().forEach { meta ->
            edit.putString("meta.${meta.id}", encodeMeta(meta.copy(lastRoutingUpdate = lastUpdate, routingStatus = status)))
        }
        check(edit.commit()) { "Не удалось сохранить состояние маршрутизации" }
    }

    @Synchronized fun create(name: String, rawConfig: String): ProfileMeta {
        val parsed = AwgConfigParser.parse(rawConfig)
        org.amnezia.awg.config.Config.parse(parsed.raw.byteInputStream())
        return createChecked(ImportedConfig(name, parsed.raw, parsed.generation))
    }

    @Synchronized fun importAll(candidates: List<ImportedConfig>): List<ProfileMeta> {
        return importValidated(validateImports(candidates))
    }

    /** Persists candidates already checked by [validateImports], keeping confirmation instant. */
    @Synchronized fun importValidated(checked: List<ImportedConfig>): List<ProfileMeta> {
        require(checked.isNotEmpty()) { "Нет конфигураций для импорта" }
        val previous = activeId()
        val created = mutableListOf<ProfileMeta>()
        try {
            checked.forEach { created += createChecked(it) }
            select(created.first().id)
            return created
        } catch (error: Throwable) {
            created.forEach { delete(it.id) }
            previous?.let(::select)
            throw error
        }
    }

    fun update(meta: ProfileMeta, rawConfig: String? = null) {
        var updated = meta
        rawConfig?.let {
            val parsed = AwgConfigParser.parse(it)
            org.amnezia.awg.config.Config.parse(parsed.raw.byteInputStream())
            updated = meta.copy(generation = parsed.generation)
            writeSecret(meta.id, parsed.raw)
        }
        saveMeta(updated)
        EventLog.add("Профиль обновлён: ${meta.name}")
    }

    fun readConfig(id: String): String {
        val blob = AtomicFile(secretFile(id)).readFully()
        require(blob.size > 13) { "Зашифрованный профиль повреждён" }
        val iv = blob.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.get(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob.copyOfRange(12, blob.size)).toString(Charsets.UTF_8)
    }

    fun delete(id: String) {
        val name = readMeta(id)?.name.orEmpty()
        AtomicFile(secretFile(id)).delete()
        val remaining = list().filterNot { it.id == id }
        val ids = remaining.mapTo(mutableSetOf()) { it.id }
        val edit = prefs.edit().remove("meta.$id").putStringSet("ids", ids)
        if (activeId() == id) edit.putString("active", remaining.firstOrNull()?.id)
        edit.apply()
        EventLog.add("Профиль удалён: $name")
    }

    fun saveMeta(meta: ProfileMeta) {
        prefs.edit().putString("meta.${meta.id}", encodeMeta(meta)).apply()
    }

    private fun encodeMeta(meta: ProfileMeta): String = JSONObject()
        .put("id", meta.id).put("name", meta.name).put("generation", meta.generation.name)
        .put("routingUrl", meta.routingUrl).put("overrides", meta.overrides)
        .put("lastRoutingUpdate", meta.lastRoutingUpdate).put("routingStatus", meta.routingStatus)
        .put("createdAt", meta.createdAt)
        .toString()

    private fun readMeta(id: String): ProfileMeta? = runCatching {
        val json = JSONObject(prefs.getString("meta.$id", null) ?: return null)
        ProfileMeta(
            id = id,
            name = json.getString("name"),
            generation = AwgGeneration.valueOf(json.getString("generation")),
            routingUrl = json.optString("routingUrl", "https://routing.help/"),
            overrides = json.optString("overrides"),
            lastRoutingUpdate = json.optLong("lastRoutingUpdate"),
            routingStatus = json.optString("routingStatus", "Ещё не проверялся"),
            createdAt = json.optLong("createdAt").takeIf { it > 0 } ?: secretFile(id).lastModified(),
        )
    }.getOrNull()

    private fun createChecked(candidate: ImportedConfig): ProfileMeta {
        val id = UUID.randomUUID().toString()
        val settings = routingSettings()
        val meta = ProfileMeta(
            id = id,
            name = candidate.name.trim().ifEmpty { "Ex WG" },
            generation = candidate.generation,
            routingUrl = settings.source,
            overrides = settings.overrides,
            createdAt = nextCreatedAt(),
        )
        writeSecret(id, candidate.config)
        saveMeta(meta)
        val ids = prefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet().also { it += id }
        check(prefs.edit().putStringSet("ids", ids).putString("active", id).commit()) { "Не удалось сохранить профиль" }
        EventLog.add("Конфигурация добавлена: ${meta.name}, AWG ${meta.generation.label}")
        return meta
    }

    private fun migrateRoutingSettings() {
        val items = rawList()
        val legacyNamed = items.filter { it.name.trim().equals(LEGACY_DEFAULT_NAME, ignoreCase = true) }
        if (legacyNamed.isNotEmpty()) {
            val renameEdit = prefs.edit()
            legacyNamed.forEach { meta ->
                renameEdit.putString("meta.${meta.id}", encodeMeta(meta.copy(name = "Ex WG")))
            }
            renameEdit.apply()
        }
        if (prefs.contains(GLOBAL_SOURCE)) {
            if (!prefs.contains(GLOBAL_LAST_UPDATE)) {
                val latest = items.maxByOrNull { it.lastRoutingUpdate }
                if (latest != null) {
                    prefs.edit()
                        .putLong(GLOBAL_LAST_UPDATE, latest.lastRoutingUpdate)
                        .putString(GLOBAL_STATUS, latest.routingStatus)
                        .apply()
                }
            }
            return
        }
        val selected = prefs.getString("active", null)?.let { id -> items.firstOrNull { it.id == id } }
        val source = selected ?: items.firstOrNull { it.routingUrl.isNotBlank() }
        val latest = items.maxByOrNull { it.lastRoutingUpdate }
        val settings = RoutingSettings(
            source = source?.routingUrl?.ifBlank { "https://routing.help/" } ?: "https://routing.help/",
            overrides = source?.overrides.orEmpty(),
            lastRoutingUpdate = latest?.lastRoutingUpdate ?: 0L,
            routingStatus = latest?.routingStatus ?: "Ещё не проверялся",
        )
        val edit = prefs.edit()
            .putString(GLOBAL_SOURCE, settings.source)
            .putString(GLOBAL_OVERRIDES, settings.overrides)
            .putLong(GLOBAL_LAST_UPDATE, settings.lastRoutingUpdate)
            .putString(GLOBAL_STATUS, settings.routingStatus)
        items.forEach { meta ->
            edit.putString("meta.${meta.id}", encodeMeta(meta.copy(routingUrl = settings.source, overrides = settings.overrides)))
        }
        check(edit.commit()) { "Не удалось перенести настройки маршрутизации" }
        EventLog.add("Настройки маршрутизации перенесены в общее хранилище: profiles=${items.size}")
    }

    private fun nextCreatedAt(): Long = maxOf(System.currentTimeMillis(), (rawList().maxOfOrNull { it.createdAt } ?: 0L) + 1L)

    private fun writeSecret(id: String, rawConfig: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (keys.isLegacy) {
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(), GCMParameterSpec(128, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, keys.get())
        }
        val encrypted = cipher.iv + cipher.doFinal(rawConfig.toByteArray())
        val atomic = AtomicFile(secretFile(id))
        val output = atomic.startWrite()
        try {
            output.write(encrypted)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun secretFile(id: String) = File(directory, "$id.bin")

    companion object {
        private const val LEGACY_DEFAULT_NAME = "\u0065uloxe"
        private const val GLOBAL_SOURCE = "routing.global.source"
        private const val GLOBAL_OVERRIDES = "routing.global.overrides"
        private const val GLOBAL_LAST_UPDATE = "routing.global.lastUpdate"
        private const val GLOBAL_STATUS = "routing.global.status"
        private const val UI_LANGUAGE = "ui.language"
    }
}
