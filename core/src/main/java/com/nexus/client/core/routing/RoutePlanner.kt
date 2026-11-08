package com.nexus.client.core.routing

import com.nexus.client.core.awg.IpPrefix
import java.net.InetAddress

/** Produces real Android/AWG IP split routes without a second userspace proxy engine. */
object RoutePlanner {
    fun apply(rawConfig: String, profile: RoutingProfile?, overrides: LocalOverrides = LocalOverrides()): String {
        if (profile == null) return rawConfig
        val hasV6 = Regex("(?im)^\\s*(Address|AllowedIPs)\\s*=.*:").containsMatchIn(rawConfig)
        val routes = if (profile.globalProxy) {
            var result = listOf(network("0.0.0.0/0")) + if (hasV6) listOf(network("::/0")) else emptyList()
            (profile.directIp + overrides.directIp).mapNotNull(::literalNetwork).forEach { cut ->
                result = result.flatMap { subtract(it, cut) }
            }
            result + overrides.proxyIp.mapNotNull(::literalNetwork)
        } else {
            var result = (profile.proxyIp + overrides.proxyIp).mapNotNull(::literalNetwork)
            overrides.directIp.mapNotNull(::literalNetwork).forEach { cut -> result = result.flatMap { subtract(it, cut) } }
            result
        }
        require(routes.isNotEmpty()) { "Routing-профиль не содержит применимых ProxyIp" }
        val value = routes.distinct().sortedWith(compareBy<Network>({ it.bytes.size }, { it.bits })).joinToString(", ")
        var peer = false
        var replaced = false
        val output = rawConfig.replace("\r\n", "\n").lines().map { source ->
            val line = source.trim()
            if (line.startsWith('[') && line.endsWith(']')) peer = line.equals("[Peer]", true)
            if (peer && line.substringBefore('=', "").trim().equals("AllowedIPs", true)) {
                replaced = true
                "AllowedIPs = $value"
            } else source
        }
        require(replaced) { "В [Peer] отсутствует AllowedIPs" }
        return output.joinToString("\n")
    }

    private data class Network(val bytes: ByteArray, val bits: Int) {
        fun contains(other: Network): Boolean = bytes.size == other.bytes.size && bits <= other.bits && IpPrefix(bytes, bits).contains(other.bytes)
        override fun equals(other: Any?): Boolean = other is Network && bits == other.bits && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + bits
        override fun toString(): String = "${InetAddress.getByAddress(bytes).hostAddress}/$bits"
    }

    private fun network(value: String): Network = literalNetwork(value)!!
    private fun literalNetwork(value: String): Network? {
        if (value.startsWith("geoip:", true)) return null
        val parsed = IpPrefix.parse(value) ?: return null
        val bytes = parsed.bytes.copyOf()
        val full = parsed.bits / 8
        val rest = parsed.bits % 8
        if (rest != 0) bytes[full] = (bytes[full].toInt() and (0xff shl (8 - rest))).toByte()
        val clearFrom = full + if (rest == 0) 0 else 1
        for (i in clearFrom until bytes.size) bytes[i] = 0
        return Network(bytes, parsed.bits)
    }

    private fun subtract(base: Network, cut: Network): List<Network> {
        if (base.bytes.size != cut.bytes.size) return listOf(base)
        val overlap = base.contains(cut) || cut.contains(base)
        if (!overlap) return listOf(base)
        if (cut.contains(base)) return emptyList()
        if (base.bits >= base.bytes.size * 8) return emptyList()
        val left = base.bytes.copyOf()
        val right = base.bytes.copyOf()
        val byteIndex = base.bits / 8
        val bitMask = 1 shl (7 - (base.bits % 8))
        right[byteIndex] = (right[byteIndex].toInt() or bitMask).toByte()
        return subtract(Network(left, base.bits + 1), cut) + subtract(Network(right, base.bits + 1), cut)
    }
}
