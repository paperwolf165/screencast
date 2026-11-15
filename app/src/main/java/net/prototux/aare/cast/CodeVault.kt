package net.prototux.aare.cast

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the phone's unlock code for the automatic unlock: AES-GCM with a key made in, and never
 * leaving, the Android keystore. What is in the app's settings file is only the ciphertext, so a
 * copy of the file (a backup, another app reading it with root) is no use off this phone.
 *
 * It does not, and cannot, protect the code from someone who holds this unlocked, rooted phone:
 * the app has to be able to read it back to type it.
 */
internal object CodeVault {
    private const val TAG = "AareCodeVault"
    private const val ALIAS = "cast_unlock_code"
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    /** [code] encrypted, as text for the settings file; null if the keystore refused. */
    fun seal(code: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        Base64.encodeToString(cipher.iv + cipher.doFinal(code.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "could not encrypt the unlock code", it) }.getOrNull()

    /** The code [sealed] holds; null if it can't be read back (the key is gone: it has to be entered again). */
    fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "could not read the unlock code back", it) }.getOrNull()
}
