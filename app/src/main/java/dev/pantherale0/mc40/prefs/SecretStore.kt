package dev.pantherale0.mc40.prefs

import android.content.Context
import android.content.SharedPreferences
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import android.util.Log
import dev.pantherale0.mc40.Mc40App
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Encrypts secrets with AES, wrapping the AES key in AndroidKeyStore RSA.
 * Falls back to plaintext prefs if the device Keystore cannot be used.
 */
class SecretStore(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private val keystoreReady = ensureKey()

    fun get(plainKey: String): String {
        val encrypted = prefs.getString(encKey(plainKey), null)
        if (!encrypted.isNullOrEmpty()) {
            val decrypted = runCatching { decrypt(encrypted) }.getOrNull()
            if (decrypted != null) return decrypted
            Log.w(Mc40App.TAG, "Failed to decrypt $plainKey")
        }
        val legacy = prefs.getString(plainKey, "") ?: ""
        if (legacy.isNotEmpty() && keystoreReady) {
            set(plainKey, legacy)
            prefs.edit().remove(plainKey).apply()
        }
        return legacy
    }

    fun set(plainKey: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(plainKey).remove(encKey(plainKey)).apply()
            return
        }
        if (keystoreReady) {
            val blob = runCatching { encrypt(trimmed) }.getOrNull()
            if (blob != null) {
                prefs.edit().putString(encKey(plainKey), blob).remove(plainKey).apply()
                return
            }
            Log.w(Mc40App.TAG, "Encrypt failed for $plainKey; storing plaintext")
        }
        prefs.edit().putString(plainKey, trimmed).remove(encKey(plainKey)).apply()
    }

    fun remove(plainKey: String) {
        prefs.edit().remove(plainKey).remove(encKey(plainKey)).apply()
    }

    private fun encKey(plainKey: String) = "enc_$plainKey"

    private fun encrypt(plain: String): String {
        val aes = ByteArray(16)
        SecureRandom().nextBytes(aes)
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aesCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(iv))
        val cipherText = aesCipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val rsa = Cipher.getInstance(RSA_TRANSFORM)
        rsa.init(Cipher.ENCRYPT_MODE, rsaPublic())
        val wrapped = rsa.doFinal(aes)
        val out = ByteArray(1 + 2 + wrapped.size + iv.size + cipherText.size)
        out[0] = VERSION
        out[1] = ((wrapped.size shr 8) and 0xFF).toByte()
        out[2] = (wrapped.size and 0xFF).toByte()
        System.arraycopy(wrapped, 0, out, 3, wrapped.size)
        System.arraycopy(iv, 0, out, 3 + wrapped.size, iv.size)
        System.arraycopy(cipherText, 0, out, 3 + wrapped.size + iv.size, cipherText.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.isNotEmpty() && raw[0] == VERSION) { "bad version" }
        val wrappedLen = ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
        require(wrappedLen in 1..512 && raw.size > 3 + wrappedLen + 16)
        val wrapped = raw.copyOfRange(3, 3 + wrappedLen)
        val iv = raw.copyOfRange(3 + wrappedLen, 3 + wrappedLen + 16)
        val cipherText = raw.copyOfRange(3 + wrappedLen + 16, raw.size)
        val rsa = Cipher.getInstance(RSA_TRANSFORM)
        rsa.init(Cipher.DECRYPT_MODE, rsaPrivate())
        val aes = rsa.doFinal(wrapped)
        val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aesCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(iv))
        return String(aesCipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun rsaPublic() = keyStore().getCertificate(ALIAS).publicKey

    private fun rsaPrivate(): java.security.PrivateKey {
        val entry = keyStore().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey
    }

    private fun keyStore(): KeyStore {
        val ks = KeyStore.getInstance(KEYSTORE)
        ks.load(null)
        return ks
    }

    private fun ensureKey(): Boolean {
        return try {
            val ks = keyStore()
            if (!ks.containsAlias(ALIAS)) {
                val start = java.util.Calendar.getInstance()
                val end = java.util.Calendar.getInstance()
                end.add(java.util.Calendar.YEAR, 25)
                @Suppress("DEPRECATION")
                val spec = KeyPairGeneratorSpec.Builder(context)
                    .setAlias(ALIAS)
                    .setSubject(X500Principal("CN=MC40HA"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .build()
                val gen = KeyPairGenerator.getInstance("RSA", KEYSTORE)
                gen.initialize(spec)
                gen.generateKeyPair()
            }
            true
        } catch (e: Exception) {
            Log.w(Mc40App.TAG, "AndroidKeyStore unavailable: ${e.message}")
            false
        }
    }

    companion object {
        private const val ALIAS = "dev.pantherale0.mc40.secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val RSA_TRANSFORM = "RSA/ECB/PKCS1Padding"
        private const val VERSION: Byte = 1
    }
}
