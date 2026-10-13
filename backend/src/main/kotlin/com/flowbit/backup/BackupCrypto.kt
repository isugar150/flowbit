package com.flowbit.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a key derived from a password (PBKDF2-HMAC-SHA256). Slow on purpose: call it off the event loop. */
object BackupCrypto {
    private const val KDF = "PBKDF2-SHA256"
    private const val ITERATIONS = 210_000

    /** A file asking for more than this is not one of ours; refusing it keeps a crafted file from tying up a core. */
    private const val MAX_ITERATIONS = 2_000_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private val random = SecureRandom()

    fun encrypt(
        plain: ByteArray,
        password: String,
    ): EncryptedBackup {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val encoder = Base64.getEncoder()
        return EncryptedBackup(KDF, ITERATIONS, encoder.encodeToString(salt), encoder.encodeToString(iv), encoder.encodeToString(cipher.doFinal(plain)))
    }

    /** Null when the password is wrong or the file was changed or is not in this format. */
    fun decrypt(
        encrypted: EncryptedBackup,
        password: String,
    ): ByteArray? {
        if (encrypted.kdf != KDF || encrypted.iterations !in 1..MAX_ITERATIONS) return null
        return try {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(encrypted.iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, decoder.decode(encrypted.salt), encrypted.iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(decoder.decode(encrypted.ciphertext))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun key(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }
}
