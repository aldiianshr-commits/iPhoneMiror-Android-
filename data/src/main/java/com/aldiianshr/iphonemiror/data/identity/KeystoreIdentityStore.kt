package com.aldiianshr.iphonemiror.data.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.aldiianshr.iphonemiror.core.AppLog
import com.aldiianshr.iphonemiror.domain.repository.IdentityStore
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the receiver's Ed25519 pairing seed encrypted with an AES-GCM key that lives in
 * the Android Keystore (never exported). The MAC-style receiver id is random (Android does
 * not expose the real Wi-Fi MAC) and stable until the user resets it.
 */
class KeystoreIdentityStore(context: Context) : IdentityStore {

    private val prefs = context.getSharedPreferences("receiver_identity", Context.MODE_PRIVATE)

    @Synchronized
    override fun loadOrCreateSeed(): ByteArray {
        prefs.getString(KEY_SEED, null)?.let { stored ->
            runCatching { return decrypt(stored) }
                .onFailure { AppLog.w(TAG, "Stored identity unreadable — generating a new one", it) }
        }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SEED, encrypt(seed))
            .putLong(KEY_CREATED, System.currentTimeMillis())
            .apply()
        return seed
    }

    @Synchronized
    override fun loadOrCreateDeviceId(): ByteArray {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val id = ByteArray(6).also { SecureRandom().nextBytes(it) }
        // Locally administered, unicast MAC-style address.
        id[0] = ((id[0].toInt() and 0xFC) or 0x02).toByte()
        prefs.edit().putString(KEY_DEVICE_ID, Base64.encodeToString(id, Base64.NO_WRAP)).apply()
        return id
    }

    override fun createdAt(): Long? = prefs.getLong(KEY_CREATED, 0L).takeIf { it > 0 }

    @Synchronized
    override fun reset() {
        prefs.edit().clear().apply()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): ByteArray {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return cipher.doFinal(raw, 12, raw.size - 12)
    }

    companion object {
        private const val TAG = "IdentityStore"
        private const val ALIAS = "iphonemiror_identity_key"
        private const val KEY_SEED = "seed"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CREATED = "created"
    }
}
