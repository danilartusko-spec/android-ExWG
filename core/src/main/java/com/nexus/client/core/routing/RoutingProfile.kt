package com.nexus.client.core.routing

import com.nexus.client.core.awg.IpPrefix
import org.json.JSONObject
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Base64

enum class RouteAction { DIRECT, PROXY, BLOCK }

data class RoutingProfile(
    val name: String,
    val globalProxy: Boolean,
    val routeOrder: List<RouteAction>,
    val directSites: List<String>,
    val directIp: List<String>,
    val proxySites: List<String>,
    val proxyIp: List<String>,
    val blockSites: List<String>,
    val blockIp: List<String>,
    val remoteDns: String?,
    val domesticDns: String?,
    val domainStrategy: String = "IPIfNonMatch",
    val lastUpdated: String = "",
    val fakeDns: Boolean = false,
    val useChunkFiles: Boolean = false,
    val remoteDnsType: String = "",
    val remoteDnsDomain: String = "",
    val remoteDnsIp: String = "",
    val domesticDnsType: String = "",
    val domesticDnsDomain: String = "",
    val domesticDnsIp: String = "",
    val geositeUrl: String = "",
    val geoipUrl: String = "",
    val dnsHosts: Map<String, String> = emptyMap(),
)

object RoutingProfileParser {
    fun parse(source: String): RoutingProfile {
        var input = source.removePrefix("\uFEFF").trim()
        require(input.isNotEmpty()) { "Профиль маршрутизации пуст" }
        if (input.startsWith("happ://", true)) {
            input = decodeHapp(input).trim()
        } else {
            val embedded = extractEmbeddedHappLink(input)
            if (embedded != null) {
                input = decodeHapp(embedded).trim()
            } else if (!input.startsWith("{")) {
                decodeBase64Payload(input)?.let { decoded ->
                    val trimmed = decoded.removePrefix("\uFEFF").trim()
                    if (trimmed.startsWith("{")) {
                        input = trimmed
                    } else if (trimmed.startsWith("happ://", true)) {
                        runCatching { decodeHapp(trimmed).trim() }.getOrNull()?.let { input = it }
                    } else {
                        extractEmbeddedHappLink(trimmed)?.let { link ->
                            runCatching { decodeHapp(link).trim() }.getOrNull()?.let { input = it }
                        }
                    }
                }
            }
        }

        if (!input.startsWith("{")) {
            parsePlainRuleProfile(input)?.let { return it }
        }

        val json = JSONObject(input)
        val keysByLower = json.keys().asSequence().associateBy { it.lowercase() }
        fun optRaw(name: String): Any? = keysByLower[name.lowercase()]?.let { json.opt(it) }
        fun optStr(name: String, default: String = ""): String =
            keysByLower[name.lowercase()]?.let { json.optString(it, default) } ?: default

        val name = optStr("Name").trim().ifEmpty { "Custom" }
        val directSites = array(json, keysByLower["directsites"])
        val directIp = array(json, keysByLower["directip"]).map(OverrideParser::normalizeIpRule)
        val proxySites = array(json, keysByLower["proxysites"])
        val proxyIp = array(json, keysByLower["proxyip"]).map(OverrideParser::normalizeIpRule)
        val blockSites = array(json, keysByLower["blocksites"])
        val blockIp = array(json, keysByLower["blockip"]).map(OverrideParser::normalizeIpRule)
        val hasGlobalProxy = "globalproxy" in keysByLower
        val globalProxy = if (hasGlobalProxy) {
            boolValue(optRaw("GlobalProxy"))
        } else {
            proxySites.isEmpty() && proxyIp.isEmpty()
        }

        var remoteDns = optStr("RemoteDns").trim().takeIf(String::isNotBlank)
        var remoteDnsType = optStr("RemoteDNSType").trim()
        var remoteDnsDomain = optStr("RemoteDNSDomain").trim()
        var remoteDnsIp = optStr("RemoteDNSIP").trim()
        if (remoteDns.isNullOrEmpty() && remoteDnsDomain.isEmpty() && remoteDnsIp.isEmpty()) {
            remoteDnsType = "doh"
            remoteDnsDomain = "dns.google"
            remoteDnsIp = "8.8.8.8"
        } else if (remoteDnsIp.isEmpty()) {
            remoteDnsIp = defaultBootstrapIp(remoteDnsDomain.ifEmpty { remoteDns.orEmpty() })
        }

        var domesticDns = optStr("DomesticDns").trim().takeIf(String::isNotBlank)
        var domesticDnsType = optStr("DomesticDNSType").trim()
        var domesticDnsDomain = optStr("DomesticDNSDomain").trim()
        var domesticDnsIp = optStr("DomesticDNSIP").trim()
        if (domesticDns.isNullOrEmpty() && domesticDnsDomain.isEmpty() && domesticDnsIp.isEmpty()) {
            domesticDnsType = "doh"
            domesticDnsDomain = "cloudflare-dns.com"
            domesticDnsIp = "1.1.1.1"
        } else if (domesticDnsIp.isEmpty()) {
            domesticDnsIp = defaultBootstrapIp(domesticDnsDomain.ifEmpty { domesticDns.orEmpty() })
        }

        val hostsObj = keysByLower["dnshosts"]?.let { json.optJSONObject(it) }
        val dnsHosts = buildMap {
            if (hostsObj != null) {
                for (hostKey in hostsObj.keys()) {
                    val addr = hostsObj.optString(hostKey).trim()
                    if (hostKey.isNotBlank() && addr.isNotBlank()) {
                        put(hostKey.trim().lowercase(), addr)
                    }
                }
            }
        }

        val profile = RoutingProfile(
            name = name,
            globalProxy = globalProxy,
            routeOrder = parseOrder(optStr("RouteOrder", "block-proxy-direct").ifBlank { "block-proxy-direct" }),
            directSites = directSites,
            directIp = directIp,
            proxySites = proxySites,
            proxyIp = proxyIp,
            blockSites = blockSites,
            blockIp = blockIp,
            remoteDns = remoteDns,
            domesticDns = domesticDns,
            domainStrategy = optStr("DomainStrategy", "IPIfNonMatch").ifBlank { "IPIfNonMatch" },
            lastUpdated = optStr("LastUpdated").trim(),
            fakeDns = boolValue(optRaw("FakeDNS")),
            useChunkFiles = boolValue(optRaw("UseChunkFiles")),
            remoteDnsType = remoteDnsType,
            remoteDnsDomain = remoteDnsDomain,
            remoteDnsIp = remoteDnsIp,
            domesticDnsType = domesticDnsType,
            domesticDnsDomain = domesticDnsDomain,
            domesticDnsIp = domesticDnsIp,
            geositeUrl = optStr("Geositeurl").trim(),
            geoipUrl = optStr("Geoipurl").trim(),
            dnsHosts = dnsHosts,
        )
        (profile.directIp + profile.proxyIp + profile.blockIp).forEach {
            require(it.startsWith("geoip:", true) || IpPrefix.parse(it) != null) { "Неверное IP-правило $it" }
        }
        return profile
    }

    fun defaultBootstrapIp(hostOrUrl: String): String {
        var host = hostOrUrl.trim()
        if ("://" in host) {
            runCatching { URI(host).host }.getOrNull()?.takeIf(String::isNotBlank)?.let { host = it }
        }
        return when (host.trim().trimEnd('.').lowercase()) {
            "dns.google", "dns.google.com" -> "8.8.8.8"
            "cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com" -> "1.1.1.1"
            "dns.quad9.net", "dns9.quad9.net" -> "9.9.9.9"
            "common.dot.dns.yandex.net", "dns.yandex.ru", "dns.yandex.com" -> "77.88.8.8"
            "dns.adguard-dns.com", "dns.adguard.com" -> "94.140.14.14"
            "doh.opendns.com" -> "208.67.222.222"
            else -> ""
        }
    }

    private fun extractEmbeddedHappLink(input: String): String? {
        val idx = input.lowercase().indexOf("happ://routing/")
        if (idx < 0) return null
        val rest = input.substring(idx)
        val end = rest.indexOfFirst { it.isWhitespace() || it in charArrayOf('"', '\'', '<', '>') }
        return (if (end >= 0) rest.substring(0, end) else rest).trim().takeIf(String::isNotEmpty)
    }

    private fun decodeBase64Payload(payload: String): String? {
        val compact = payload.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return null
        return runCatching { String(Base64.getDecoder().decode(compact)) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(compact)) }
            .getOrNull()
    }

    private fun parsePlainRuleProfile(input: String): RoutingProfile? {
        val overrides = runCatching { OverrideParser.parse(input) }.getOrNull() ?: return null
        val count = overrides.directSites.size + overrides.directIp.size +
            overrides.proxySites.size + overrides.proxyIp.size +
            overrides.blockSites.size + overrides.blockIp.size + overrides.dns.size
        if (count == 0) return null

        fun parseDnsEndpoint(raw: String?, defaultType: String, defaultDomain: String, defaultIp: String): Triple<String?, String, String> {
            if (raw.isNullOrBlank()) return Triple(null, defaultType, defaultIp)
            val parts = raw.trim().split(Regex("\\s+"))
            val server = parts[0]
            val ip = if (parts.size >= 2) parts[1] else defaultBootstrapIp(server).ifEmpty { defaultIp }
            val scheme = runCatching { URI(server).scheme?.lowercase() }.getOrNull()
            val type = when (scheme) {
                "https" -> "doh"
                "tls" -> "dot"
                "tcp" -> "tcp"
                "udp" -> "udp"
                else -> defaultType
            }
            return Triple(server, type, ip)
        }

        val (domesticServer, domesticType, domesticIp) = parseDnsEndpoint(
            overrides.dns["dns-direct"]?.lastOrNull(), "doh", "cloudflare-dns.com", "1.1.1.1"
        )
        val (remoteServer, remoteType, remoteIp) = parseDnsEndpoint(
            overrides.dns["dns-proxy"]?.lastOrNull(), "doh", "dns.google", "8.8.8.8"
        )
        return RoutingProfile(
            name = "Custom",
            globalProxy = true,
            routeOrder = listOf(RouteAction.BLOCK, RouteAction.PROXY, RouteAction.DIRECT),
            directSites = overrides.directSites,
            directIp = overrides.directIp,
            proxySites = overrides.proxySites,
            proxyIp = overrides.proxyIp,
            blockSites = overrides.blockSites,
            blockIp = overrides.blockIp,
            remoteDns = remoteServer,
            domesticDns = domesticServer,
            remoteDnsType = remoteType,
            remoteDnsDomain = if (remoteServer == null) "dns.google" else "",
            remoteDnsIp = remoteIp,
            domesticDnsType = domesticType,
            domesticDnsDomain = if (domesticServer == null) "cloudflare-dns.com" else "",
            domesticDnsIp = domesticIp,
        )
    }

    private fun decodeHapp(value: String): String {
        val uri = URI(value)
        require(uri.scheme.equals("happ", true) && uri.host.equals("routing", true)) { "Поддерживаются только happ://routing/add и onadd" }
        val parts = uri.rawPath.trim('/').split('/', limit = 2)
        require(parts.size == 2 && parts[0] in setOf("add", "onadd")) { "Неверный формат Happ-маршрутизации" }
        val payload = java.net.URLDecoder.decode(parts[1].replace("+", "%2B"), "UTF-8")
        return decodeBase64Payload(payload) ?: error("Профиль в ссылке не является Base64")
    }

    private fun boolValue(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value.trim().toBooleanStrictOrNull() ?: false
        else -> false
    }

    private fun array(json: JSONObject, key: String?): List<String> {
        if (key == null) return emptyList()
        val values = json.optJSONArray(key) ?: return emptyList()
        return buildList { for (i in 0 until values.length()) values.optString(i).trim().takeIf(String::isNotEmpty)?.let(::add) }
    }

    private fun parseOrder(value: String): List<RouteAction> {
        val result = value.split('-').map { RouteAction.valueOf(it.trim().uppercase()) }
        require(result.size == 3 && result.toSet().size == 3) { "RouteOrder должен содержать direct, proxy и block" }
        return result
    }
}

data class LocalOverrides(
    val directSites: List<String> = emptyList(), val directIp: List<String> = emptyList(),
    val proxySites: List<String> = emptyList(), val proxyIp: List<String> = emptyList(),
    val blockSites: List<String> = emptyList(), val blockIp: List<String> = emptyList(),
    val dns: Map<String, List<String>> = emptyMap(),
)

object OverrideParser {
    private val knownDnsKeys = setOf("dns-direct", "dns-proxy", "dns-direct-fallback", "dns-proxy-fallback")
    private val ipv4Literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun normalizeIpRule(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("geoip:", true) || '/' in trimmed) return trimmed
        if (ipv4Literal.matches(trimmed) && IpPrefix.parse("$trimmed/32") != null) return "$trimmed/32"
        if (':' in trimmed && !trimmed.startsWith("geosite:", true) && IpPrefix.parse("$trimmed/128") != null) return "$trimmed/128"
        return trimmed
    }

    fun normalizeRuleTarget(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        val firstSpace = value.indexOfFirst(Char::isWhitespace)
        if (firstSpace > 0) {
            val head = value.substring(0, firstSpace).lowercase()
            if (head in setOf("direct", "proxy", "block")) {
                value = value.substring(firstSpace).trim()
            }
        }
        val lower = value.lowercase()
        for (prefix in listOf("geosite:", "geoip:", "domain:", "full:", "keyword:", "regexp:", "regex:")) {
            if (lower.startsWith(prefix)) {
                val body = value.substring(prefix.length).trim()
                return if (body.isEmpty()) "" else "$prefix$body"
            }
        }
        if ("://" in value) {
            runCatching { URI(value).host }.getOrNull()?.takeIf(String::isNotBlank)?.let { value = it }
        } else if ('/' in value) {
            val asIpPrefix = IpPrefix.parse(value)
            if (asIpPrefix != null) return value
            runCatching { URI("https://$value").host }.getOrNull()?.takeIf(String::isNotBlank)?.let { value = it }
        }
        val maybeIp = normalizeIpRule(value)
        if (IpPrefix.parse(maybeIp) != null) return maybeIp
        val host = value.trim().trimEnd('.').lowercase()
        return if (host.isEmpty()) "" else "domain:$host"
    }

    fun assignRule(overridesText: String, ruleTarget: String, action: RouteAction?): String {
        val normalizedTarget = normalizeRuleTarget(ruleTarget)
        if (normalizedTarget.isEmpty()) return overridesText
        val rawTrimmed = ruleTarget.trim().lowercase()
        val kept = mutableListOf<String>()
        for (rawLine in overridesText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) {
                if (trimmed.isNotEmpty()) kept.add(rawLine)
                continue
            }
            val pieces = trimmed.split(Regex("\\s+"), limit = 2)
            if (pieces.size == 2 && pieces[0].lowercase() in setOf("direct", "proxy", "block")) {
                val existingRaw = pieces[1].trim().lowercase()
                val existingNormalized = normalizeRuleTarget(pieces[1]).lowercase()
                if (existingRaw == rawTrimmed || existingRaw == normalizedTarget.lowercase() || existingNormalized == normalizedTarget.lowercase()) {
                    continue
                }
            }
            kept.add(trimmed)
        }
        if (action != null) {
            kept.add("${action.name.lowercase()} $normalizedTarget")
        }
        return kept.joinToString("\n")
    }

    fun parse(input: String): LocalOverrides {
        val sites = RouteAction.entries.associateWith { mutableListOf<String>() }
        val ips = RouteAction.entries.associateWith { mutableListOf<String>() }
        val dns = mutableMapOf<String, MutableList<String>>()
        input.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val pieces = line.split(Regex("\\s+"), limit = 2)
            require(pieces.size == 2) { "Строка ${index + 1}: ожидается действие и правило" }
            val actionKey = pieces[0].lowercase()
            if (actionKey.startsWith("dns-")) {
                require(actionKey in knownDnsKeys) { "Строка ${index + 1}: неизвестная DNS-настройка" }
                val dnsParts = pieces[1].trim().split(Regex("\\s+"))
                require(dnsParts.size in 1..2) { "Строка ${index + 1}: DNS URL и необязательный bootstrap IP" }
                dns.getOrPut(actionKey) { mutableListOf() }.add(pieces[1].trim())
                return@forEachIndexed
            }
            val action = runCatching { RouteAction.valueOf(pieces[0].uppercase()) }.getOrNull()
            requireNotNull(action) { "Строка ${index + 1}: неизвестное действие" }
            val rawValue = pieces[1].trim()
            val normalizedIp = normalizeIpRule(rawValue)
            val isIp = normalizedIp.startsWith("geoip:", true) || IpPrefix.parse(normalizedIp) != null
            val finalValue = if (isIp) {
                normalizedIp
            } else if (rawValue.startsWith("geosite:", true) || ':' in rawValue) {
                rawValue
            } else {
                "domain:${rawValue.trimEnd('.').lowercase()}"
            }
            (if (isIp) ips else sites).getValue(action).add(finalValue)
        }
        return LocalOverrides(
            sites.getValue(RouteAction.DIRECT), ips.getValue(RouteAction.DIRECT),
            sites.getValue(RouteAction.PROXY), ips.getValue(RouteAction.PROXY),
            sites.getValue(RouteAction.BLOCK), ips.getValue(RouteAction.BLOCK),
            dns,
        )
    }
}


class RoutingMatcher(profile: RoutingProfile, overrides: LocalOverrides = LocalOverrides()) {
    private val order = profile.routeOrder
    private val default = if (profile.globalProxy) RouteAction.PROXY else RouteAction.DIRECT
    private val remoteDomains = mapOf(
        RouteAction.DIRECT to profile.directSites,
        RouteAction.PROXY to profile.proxySites,
        RouteAction.BLOCK to profile.blockSites,
    )
    private val localDomains = mapOf(RouteAction.DIRECT to overrides.directSites, RouteAction.PROXY to overrides.proxySites, RouteAction.BLOCK to overrides.blockSites)
    private val remoteIps = mapOf(RouteAction.DIRECT to profile.directIp, RouteAction.PROXY to profile.proxyIp, RouteAction.BLOCK to profile.blockIp)
    private val localIps = mapOf(RouteAction.DIRECT to overrides.directIp, RouteAction.PROXY to overrides.proxyIp, RouteAction.BLOCK to overrides.blockIp)

    fun domain(value: String): RouteAction = matchDomain(value, localDomains) ?: matchDomain(value, remoteDomains) ?: default

    fun ip(value: String): RouteAction {
        val bytes = runCatching { InetAddress.getByName(value).address }.getOrNull() ?: return default
        return matchIp(bytes, localIps) ?: matchIp(bytes, remoteIps) ?: default
    }

    private fun matchDomain(value: String, rules: Map<RouteAction, List<String>>): RouteAction? {
        val domain = normalize(value)
        order.forEach { action -> if (rules[action].orEmpty().any { domainRule(it, domain) }) return action }
        return null
    }

    private fun domainRule(source: String, domain: String): Boolean {
        val rule = source.trim()
        if (rule.startsWith("geosite:", true)) return false
        val prefix = rule.substringBefore(':', "domain").lowercase()
        val body = normalize(if (':' in rule) rule.substringAfter(':') else rule)
        return when (prefix) {
            "full" -> domain == body
            "keyword" -> domain.contains(body)
            "regexp", "regex" -> runCatching { Regex(body, RegexOption.IGNORE_CASE).containsMatchIn(domain) }.getOrDefault(false)
            else -> domain == body || domain.endsWith(".$body")
        }
    }

    private fun matchIp(bytes: ByteArray, rules: Map<RouteAction, List<String>>): RouteAction? {
        order.forEach { action -> if (rules[action].orEmpty().any { !it.startsWith("geoip:", true) && IpPrefix.parse(it)?.contains(bytes) == true }) return action }
        return null
    }

    private fun normalize(value: String): String = IDN.toASCII(value.trim().trimEnd('.').lowercase())
}
