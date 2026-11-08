package com.nexus.client.routing

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal object LegacyHttps {
    fun configure(context: Context, builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (Build.VERSION.SDK_INT >= 26) return builder
        // Retain the OS roots and add only the missing, established ISRG roots.
        // The platform TrustManager still verifies chains and validity; OkHttp's
        // default hostname verifier is unchanged. Never trust arbitrary chains.
        val defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val system = defaults.trustManagers.filterIsInstance<X509TrustManager>().single()
        system.acceptedIssuers.forEachIndexed { index, certificate -> trustStore.setCertificateEntry("system-$index", certificate) }
        val certificates = CertificateFactory.getInstance("X.509")
        for (name in listOf("isrgrootx1.pem", "isrgrootx2.pem")) {
            context.assets.open(name).use { trustStore.setCertificateEntry(name, certificates.generateCertificate(it)) }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return builder.sslSocketFactory(ssl.socketFactory, manager)
    }
}
