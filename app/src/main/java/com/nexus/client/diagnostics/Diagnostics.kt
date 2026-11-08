package com.nexus.client.diagnostics

import android.content.Context
import android.net.VpnService
import com.nexus.client.NexusApplication
import com.nexus.client.EventLog
import com.nexus.client.BuildConfig
import com.nexus.client.core.awg.AwgConfigParser
import com.nexus.client.core.routing.RoutingProfile
import com.nexus.client.core.security.SecretMasker
import com.nexus.client.tunnel.ConnectionPhase
import com.nexus.client.tunnel.TunnelSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CheckLevel { PASS, WARN, FAIL }
data class DiagnosticCheck(val name: String, val level: CheckLevel, val detail: String)
data class DiagnosticReport(val checks: List<DiagnosticCheck>, val reason: String, val debug: String) {
    fun fullText(): String = SecretMasker.maskSecretsOnly(buildString {
        appendLine("Ex WG — полная локальная диагностика")
        appendLine(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(Date()))
        checks.forEach { appendLine("${it.level}: ${it.name}: ${it.detail}") }
        appendLine("Вероятная причина: $reason")
        appendLine(debug)
        appendLine("Журнал может содержать адреса серверов и сведения об устройстве. Проверяйте его перед отправкой.")
    })
}

object DiagnosticsRunner {
    suspend fun run(context: Context, rawConfig: String?, snapshot: TunnelSnapshot, routing: RoutingProfile?, overrides: String, extraDomain: String? = null): DiagnosticReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext as NexusApplication
        val checks = mutableListOf<DiagnosticCheck>()
        val config = runCatching { requireNotNull(rawConfig); AwgConfigParser.parse(rawConfig) }
        checks += DiagnosticCheck("AWG-конфигурация", if (rawConfig == null) CheckLevel.WARN else if (config.isSuccess) CheckLevel.PASS else CheckLevel.FAIL,
            if (rawConfig == null) "Профиль не выбран; проверка конфигурации не выполнялась" else
                config.getOrNull()?.let { "поколение ${it.generation.label}, один peer" } ?: config.exceptionOrNull()!!.stackTraceToString())
        val permission = VpnService.prepare(context) == null
        checks += DiagnosticCheck("VPN-разрешение", if (permission) CheckLevel.PASS else CheckLevel.FAIL, if (permission) "выдано" else "не выдано")
        val running = app.tunnel.state.value.tunnelActive
        checks += DiagnosticCheck("VpnService / TUN", if (running) CheckLevel.PASS else CheckLevel.FAIL, if (running) "служба и TUN активны" else "VPN не подключён")
        suspend fun probe(domain: String): JSONObject? = if (!running) null else try {
            app.tunnel.probeDomain(domain)
        } catch (error: Exception) {
            EventLog.error("diagnostics.probe.$domain", error)
            JSONObject().put("ok", false).put("error", error.stackTraceToString())
        }
        val dns = if (running) runCatching { app.tunnel.probeDNS() }.getOrElse { EventLog.error("diagnostics.dns", it); JSONObject() } else JSONObject()
        for ((key, title) in listOf("direct" to "DNS напрямую (Domestic)", "proxy" to "DNS через VPN (Remote)")) {
            val result = dns.optJSONObject(key)
            checks += DiagnosticCheck(title, if (!running) CheckLevel.WARN else if (result?.optBoolean("ok") == true) CheckLevel.PASS else CheckLevel.FAIL,
                result?.toString(2) ?: "Проверка DNS не выполнена")
        }
        val russian = probe("yandex.ru")
        val foreign = probe("example.com")
        fun routeCheck(name: String, probe: JSONObject?) {
            val ok = probe?.optBoolean("ok") == true
            checks += DiagnosticCheck(name, if (ok) CheckLevel.PASS else if (!running) CheckLevel.WARN else CheckLevel.FAIL,
                if (probe == null) "не проверялся: VPN не подключён" else
                    probe.toString(2))
        }
        routeCheck("Российский маршрут", russian)
        routeCheck("Иностранный маршрут", foreign)
        extraDomain?.trim()?.takeIf { it.isNotBlank() && it != "yandex.ru" && it != "example.com" }?.let { routeCheck("Ваш ресурс: $it", probe(it)) }
        if (running) delay(1600)
        val now = app.tunnel.state.value
        checks += DiagnosticCheck("Движок AWG", if (running) CheckLevel.PASS else CheckLevel.WARN, now.backendVersion)
        checks += DiagnosticCheck("Handshake", if (now.phase == ConnectionPhase.CONNECTED) CheckLevel.PASS else CheckLevel.WARN,
            "phase=${now.phase}; last_epoch_sec=${now.lastHandshakeEpochSeconds}; age_monotonic_sec=${now.handshakeAgeSeconds}; error=${now.error}")
        checks += DiagnosticCheck("Трафик AWG", if (now.rxBytes > 0) CheckLevel.PASS else CheckLevel.WARN,
            "получено ${formatBytes(now.rxBytes)}, отправлено ${formatBytes(now.txBytes)}")
        val dnsFailed = running && (dns.optJSONObject("direct")?.optBoolean("ok") != true || dns.optJSONObject("proxy")?.optBoolean("ok") != true)
        checks += DiagnosticCheck("Внутренний DNS", if (!running) CheckLevel.WARN else if (dnsFailed) CheckLevel.FAIL else if (now.dnsErrors > 0) CheckLevel.WARN else CheckLevel.PASS,
            if (!running) "не активен" else "запросов приложений ${now.dnsQueries}, ошибок ${now.dnsErrors}, подряд ошибок Proxy DNS ${now.proxyDnsFailures}, ответов из кеша ${now.dnsCacheHits}; общие счётчики накопительные, не процент ошибок")
        checks += DiagnosticCheck("Маршрутизатор", if (running) CheckLevel.PASS else CheckLevel.WARN,
            "Direct ${now.directFlows}, Proxy ${now.proxyFlows}, Block ${now.blockedFlows}")
        val reason = when {
            config.isFailure -> "Сначала добавьте корректную AWG-конфигурацию."
            !permission -> "VPN-разрешение не выдано. Подтвердите системный запрос."
            !running -> now.error ?: "VPN-служба не запущена."
            now.phase != ConnectionPhase.CONNECTED -> "Свежий handshake не подтверждён. Туннель сохранён, попытки продолжаются. Недоступный сервер, блокировку UDP и несовпадающие ключи нельзя достоверно различить только по отсутствию ответа."
            now.rxBytes == 0L -> "Handshake есть, но сервер не возвращает полезный трафик."
            dnsFailed -> "Не прошла отдельная проверка Domestic или Remote DNS. Смотрите resolver, route и исходную ошибку выше; исправный handshake этого не исключает."
            russian?.optString("stage") == "DNS" || foreign?.optString("stage") == "DNS" -> "Не удалось разрешить домен проверяемого ресурса. Смотрите DNS-ошибку и применённый маршрут."
            russian?.optBoolean("ok") != true || foreign?.optBoolean("ok") != true -> "Одна из HTTPS-проверок не прошла: проверьте Block-правила, доступность сайта и MTU."
            else -> "Проверки DNS, HTTPS и AWG выполнены. Ошибок в проверенных маршрутах не обнаружено."
        }
        val debug = buildString {
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); debug=${BuildConfig.DEBUG}")
            appendLine("installation=${app.currentStorageSummary()}")
            appendLine("traffic.scope=all apps; IPv4+IPv6 TUN; TCP+UDP; HTTP/TLS route-only sniff; QUIC uses DNS/IP rules (no QUIC ClientHello parser); ECH inner SNI is not decrypted")
            appendLine("Android=${android.os.Build.VERSION.RELEASE}; API=${android.os.Build.VERSION.SDK_INT}; ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; fingerprint=${android.os.Build.FINGERPRINT}")
            appendLine("snapshot.start=$snapshot")
            appendLine("snapshot.end=$now")
            appendLine("routing.name=${routing?.name}; override_lines=${overrides.lines().count { it.isNotBlank() }}")
            appendLine("diagnostics.history=${EventLog.historySummary()}")
            val routingSettings = app.profiles.routingSettings()
            appendLine("routing.lastUpdate=${routingSettings.lastRoutingUpdate}; status=${routingSettings.routingStatus}")
            appendLine("routing.effective=${runCatching { app.routing.summary(routingSettings.overrides).toString(2) }.getOrElse { it.stackTraceToString() }}")
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            manager.allNetworks.forEach { network ->
                appendLine("network=$network; capabilities=${manager.getNetworkCapabilities(network)}; links=${manager.getLinkProperties(network)}")
            }
            appendLine("--- recent original local log (UI preview bounded to 512K characters; daily lossless history is exported separately) ---")
            appendLine(EventLog.export())
        }
        DiagnosticReport(checks, reason, debug)
    }
    private fun formatAge(epochSeconds: Long): String {
        val seconds = ((System.currentTimeMillis() / 1000) - epochSeconds).coerceAtLeast(0)
        return if (seconds < 60) "$seconds сек назад" else "${seconds / 60} мин назад"
    }
    fun formatBytes(value: Long): String {
        if (value < 1024) return "$value Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var number = value.toDouble()
        var index = -1
        while (number >= 1024 && index < units.lastIndex) { number /= 1024; index++ }
        return String.format(Locale.ROOT, "%.1f %s", number, units[index])
    }
}
