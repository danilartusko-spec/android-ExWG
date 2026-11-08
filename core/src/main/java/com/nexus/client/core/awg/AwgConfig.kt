package com.nexus.client.core.awg

import java.util.Base64

enum class AwgGeneration(val label: String) { AWG_21("2.1"), AWG_30("3.0"), AWG_31("3.1") }

data class AwgField(val name: String, val value: String, val line: Int)
data class AwgSection(val name: String, val fields: MutableMap<String, AwgField> = linkedMapOf())
data class AwgConfig(
    val raw: String,
    val interfaceSection: AwgSection,
    val peers: List<AwgSection>,
    val generation: AwgGeneration,
    val warnings: List<String>,
)

object AwgConfigParser {
    private val forbidden = setOf("preup", "postup", "predown", "postdown")
    private val v30 = setOf("headerprotectionkey", "contentpaddingaddition", "rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts")
    private val v31 = setOf("randomtrailers", "disablecookies")

    fun parse(source: String): AwgConfig {
        require(source.toByteArray().size <= 256 * 1024) { "Конфигурация превышает 256 КБ" }
        val raw = source.removePrefix("\uFEFF").trim()
        require(raw.isNotEmpty()) { "Конфигурация пуста" }
        var iface: AwgSection? = null
        val peers = mutableListOf<AwgSection>()
        var current: AwgSection? = null
        val warnings = mutableListOf<String>()
        raw.replace("\r\n", "\n").lines().forEachIndexed { index, sourceLine ->
            val lineNumber = index + 1
            val line = sourceLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEachIndexed
            if (line.startsWith('[') && line.endsWith(']')) {
                current = when (line.substring(1, line.lastIndex).trim().lowercase()) {
                    "interface" -> {
                        require(iface == null) { "Строка $lineNumber: секция [Interface] повторяется" }
                        AwgSection("Interface").also { iface = it }
                    }
                    "peer" -> AwgSection("Peer").also(peers::add)
                    else -> error("Строка $lineNumber: неизвестная секция $line")
                }
                return@forEachIndexed
            }
            val split = line.indexOf('=')
            require(split > 0 && split < line.lastIndex) { "Строка $lineNumber: ожидается Имя = значение" }
            val name = line.substring(0, split).trim()
            val value = line.substring(split + 1).trim()
            require(name.isNotEmpty() && value.isNotEmpty()) { "Строка $lineNumber: имя и значение не могут быть пустыми" }
            val section = requireNotNull(current) { "Строка $lineNumber: параметр вне секции" }
            val normalized = name.lowercase()
            require(normalized !in forbidden) { "Строка $lineNumber: $name запрещён из-за выполнения внешних команд" }
            section.fields[normalized]?.let { warnings += "$name на строке $lineNumber заменяет строку ${it.line}" }
            section.fields[normalized] = AwgField(name, value, lineNumber)
        }
        val interfaceSection = requireNotNull(iface) { "Отсутствует секция [Interface]" }
        require(peers.size == 1) { "На первом этапе поддерживается ровно один [Peer]" }
        requireKey(interfaceSection, "privatekey", "PrivateKey")
        interfaceSection.fields["headerprotectionkey"]?.let {
            require(validKey(it.value)) { "HeaderProtectionKey должен содержать 32 байта" }
            for (padding in listOf("s1", "s2", "s3", "s4")) {
                require((interfaceSection.fields[padding]?.value?.toIntOrNull() ?: -1) >= 12) {
                    "HeaderProtectionKey требует ${padding.uppercase()} не меньше 12"
                }
            }
        }
        setOf("randomtrailers", "disablecookies").forEach { key ->
            interfaceSection.fields[key]?.let { require(it.value.lowercase() in setOf("true", "false", "1", "0", "on", "off", "yes", "no")) { "${it.name}: ожидается true/false или on/off" } }
        }
        interfaceSection.fields["mtu"]?.let { require(it.value.toIntOrNull() in 1280..1500) { "MTU должен быть от 1280 до 1500" } }
        requirePrefixes(interfaceSection, "address", "Address")
        val peer = peers.single()
        requireKey(peer, "publickey", "PublicKey")
        peer.fields["presharedkey"]?.let { require(validKey(it.value)) { "Строка ${it.line}: PresharedKey должен содержать 32 байта" } }
        requirePrefixes(peer, "allowedips", "AllowedIPs")
        validateEndpoint(requireNotNull(peer.fields["endpoint"]) { "Отсутствует Endpoint" })
        val generation = when {
            interfaceSection.fields.keys.any(v31::contains) -> AwgGeneration.AWG_31
            interfaceSection.fields.keys.any(v30::contains) -> AwgGeneration.AWG_30
            else -> AwgGeneration.AWG_21
        }
        return AwgConfig(raw, interfaceSection, peers, generation, warnings)
    }

    private fun requireKey(section: AwgSection, key: String, label: String) {
        val field = requireNotNull(section.fields[key]) { "Отсутствует $label" }
        require(validKey(field.value)) { "Строка ${field.line}: $label должен содержать 32 байта" }
    }

    private fun validKey(value: String): Boolean = runCatching { Base64.getDecoder().decode(value) }.getOrNull()?.size == 32

    private fun requirePrefixes(section: AwgSection, key: String, label: String) {
        val field = requireNotNull(section.fields[key]) { "Отсутствует $label" }
        field.value.split(',').forEach { require(IpPrefix.parse(it.trim()) != null) { "Строка ${field.line}: неверная сеть ${it.trim()}" } }
    }

    private fun validateEndpoint(field: AwgField) {
        val value = field.value
        val port = if (value.startsWith('[')) value.substringAfterLast("]:", "") else value.substringAfterLast(':', "")
        val host = if (value.startsWith('[')) value.substringAfter('[').substringBefore(']') else value.substringBeforeLast(':', "")
        val portNumber = port.toIntOrNull()
        require(host.isNotBlank() && portNumber != null && portNumber in 1..65535) { "Строка ${field.line}: неверный Endpoint" }
    }
}

data class IpPrefix(val bytes: ByteArray, val bits: Int) {
    fun contains(address: ByteArray): Boolean {
        if (address.size != bytes.size) return false
        val full = bits / 8
        val rest = bits % 8
        for (i in 0 until full) if (address[i] != bytes[i]) return false
        if (rest == 0) return true
        val mask = (0xff shl (8 - rest)) and 0xff
        return (address[full].toInt() and mask) == (bytes[full].toInt() and mask)
    }

    override fun equals(other: Any?): Boolean = other is IpPrefix && bits == other.bits && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + bits

    companion object {
        fun parse(value: String): IpPrefix? = runCatching {
            val parts = value.split('/', limit = 2)
            require(parts[0].isNotBlank() && (parts[0].contains(':') && parts[0].all { it in "0123456789abcdefABCDEF:." } ||
                parts[0].split('.').let { octets -> octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 } }))
            val address = java.net.InetAddress.getByName(parts[0]).address
            val bits = parts.getOrNull(1)?.toInt() ?: address.size * 8
            require(bits in 0..address.size * 8)
            IpPrefix(address, bits)
        }.getOrNull()
    }
}
