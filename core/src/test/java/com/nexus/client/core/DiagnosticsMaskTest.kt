package com.nexus.client.core

import com.nexus.client.core.security.SecretMasker
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsMaskTest {
    @Test fun debugKeepsOriginalAddressErrnoAndStackButMasksKeys() {
        val trace = "java.net.ConnectException: connect 192.0.2.1:443 errno=101\n at foo.Bar(Bar.kt:42)\nPrivateKey = ${"A".repeat(43)}="
        val result = SecretMasker.maskSecretsOnly(trace)
        assertTrue(result.contains("192.0.2.1:443 errno=101"))
        assertTrue(result.contains(" at foo.Bar(Bar.kt:42)"))
        assertFalse(result.contains("A".repeat(43)))
        val mappingId = "94854bf35194e71d71904398781d7583c419f8dfb6e79310fbc87da251d4788c"
        assertEquals("r8-map-id-$mappingId:42", SecretMasker.maskSecretsOnly("r8-map-id-$mappingId:42"))
    }
}
