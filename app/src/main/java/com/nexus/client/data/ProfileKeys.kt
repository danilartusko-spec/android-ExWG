package com.nexus.client.data

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/** API 23+: non-exportable AES key. API 21/22: RSA-wrapped software AES key.
 * No plaintext key is ever persisted. Keep the legacy format after an OS upgrade
 * so existing profiles remain readable; never silently replace a missing key.
 */
internal class ProfileKeys(private val context: Context) {
    private val wrapped = AtomicFile(File(context.noBackupFilesDir, "nexus-profile-aes.rsa"))
    val isLegacy: Boolean get() = Build.VERSION.SDK_INT < 23 || wrapped.baseFile.exists() || File(wrapped.baseFile.path + ".bak").exists()

    fun get(): SecretKey = synchronized(LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (Build.VERSION.SDK_INT < 23 || isLegacy) legacy(store) else ModernKey.get(store)
    }

    @Suppress("DEPRECATION")
    private fun legacy(store: KeyStore): SecretKey {
        val exists = wrapped.baseFile.exists() || File(wrapped.baseFile.path + ".bak").exists()
        if (!store.containsAlias(LEGACY_ALIAS)) {
            check(!exists) { "Системный ключ утрачен; импортируйте конфигурацию заново" }
            val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(LEGACY_ALIAS).setKeySize(2048)
                .setSubject(X500Principal("CN=Nexus local profiles"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(Date(0)).setEndDate(Date(4_102_444_800_000L)).build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        if (!exists) {
            val plain = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, store.getCertificate(LEGACY_ALIAS).publicKey)
                val encrypted = cipher.doFinal(plain)
                val output = wrapped.startWrite()
                try { output.write(encrypted); wrapped.finishWrite(output) }
                catch (error: Throwable) { wrapped.failWrite(output); throw error }
            } finally { plain.fill(0) }
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, store.getKey(LEGACY_ALIAS, null))
        val plain = cipher.doFinal(wrapped.readFully())
        try {
            check(plain.size == 32) { "Ключ профилей повреждён" }
            return SecretKeySpec(plain, "AES")
        } finally { plain.fill(0) }
    }

    // Isolated from the API 21 class-loading path.
    @RequiresApi(23)
    private object ModernKey {
        fun get(store: KeyStore): SecretKey {
            (store.getKey("nexus_profile_key_v1", null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder("nexus_profile_key_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        }
    }

    companion object {
        private val LOCK = Any()
        private const val LEGACY_ALIAS = "nexus_profile_rsa_v1"
    }
}
