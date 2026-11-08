package com.nexus.client.core.security

object SecretMasker {
    private val secretLine = Regex("(?im)^\\s*(PrivateKey|PresharedKey|HeaderProtectionKey|private_key|preshared_key)\\s*[:=]\\s*.+$")
    private val endpointLine = Regex("(?im)^(\\s*Endpoint\\s*=\\s*)([^\\s:]+|\\[[^]]+])(:\\d+).*$")
    private val token = Regex("(?i)(token|authorization|password|secret)(\\s*[:=]\\s*)([^\\r\\n,;]+)")
    private val keyValue = Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/])|(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
    private val hostPort = Regex("(?i)(?:\\[[0-9a-f:]+]|(?:[a-z0-9-]+\\.)+[a-z0-9-]+):[0-9]{1,5}")
    private val debugBase64Key = Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/])")

    fun mask(input: String): String = input
        .replace(secretLine) { "${it.groupValues[1]} = [СКРЫТО]" }
        .replace(endpointLine) { "${it.groupValues[1]}[СКРЫТО]${it.groupValues[3]}" }
        .replace(token) { "${it.groupValues[1]}${it.groupValues[2]}[СКРЫТО]" }
        .replace(keyValue, "[СКРЫТО]")
        .replace(hostPort, "[ENDPOINT СКРЫТ]")

    /** Debug mode retains endpoint/error codes, but not authentication material. */
    fun maskSecretsOnly(input: String): String = input
        .replace(secretLine) { "${it.groupValues[1]} = [СКРЫТО]" }
        .replace(token) { "${it.groupValues[1]}${it.groupValues[2]}[СКРЫТО]" }
        .replace(debugBase64Key, "[СКРЫТО]")
}
