package com.flowbit.common.crypto

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("flowbit.secrets")
data class SecretProperties(
    /**
     * The passphrase the secrets kept in the database (LDAP and SSO secrets, the mail password, the
     * Firebase key, webhook secrets, Git secrets) are encrypted with. Empty: they stay as they are
     * (docs/features/account-security.md).
     */
    val key: String = "",
)

/**
 * AES-256-GCM for the secrets in the database. A stored secret reads `enc:v1:` and then the
 * Base64 of a fresh random 12-byte IV followed by the encrypted bytes and the GCM tag, so the same
 * secret never looks the same twice and a changed value is noticed.
 *
 * A value without the `enc:v1:` prefix is a secret from before encryption was turned on: it is read
 * as it is and encrypted by [SecretMigration]. Without a key nothing is encrypted; a value that is
 * already encrypted cannot be read then, and says so.
 */
@Component
class SecretCipher(
    properties: SecretProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val key: SecretKeySpec? =
        properties.key
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let(::derive)

    val enabled: Boolean
        get() = key != null

    init {
        if (key == null) {
            log.warn("FLOWBIT_SECRET_KEY is not set: secrets in the database (LDAP/SSO/mail/Firebase/webhook/Git) are stored as plain text")
        }
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    /** The form to store. An empty secret stays empty so that "not set" can still be told in SQL. */
    fun encrypt(plain: String): String {
        val secretKey = key ?: return plain
        if (plain.isEmpty()) return plain
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** The secret itself, from what is stored. */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        val secretKey =
            checkNotNull(key) { "A secret in the database is encrypted but FLOWBIT_SECRET_KEY is not set. Set the key it was encrypted with." }
        val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        check(bytes.size > IV_BYTES) { "A stored secret is cut short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        return try {
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalStateException("A secret in the database cannot be decrypted: FLOWBIT_SECRET_KEY is not the key it was encrypted with", e)
        }
    }

    private fun derive(passphrase: String): SecretKeySpec {
        check(passphrase.length >= MIN_KEY_LENGTH) { "FLOWBIT_SECRET_KEY must be at least $MIN_KEY_LENGTH characters" }
        val spec = PBEKeySpec(passphrase.toCharArray(), SALT, ITERATIONS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    companion object {
        const val PREFIX = "enc:v1:"
        const val MIN_KEY_LENGTH = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val ITERATIONS = 200_000
        private val SALT = "flowbit-secrets-v1".toByteArray(Charsets.UTF_8)
    }
}
