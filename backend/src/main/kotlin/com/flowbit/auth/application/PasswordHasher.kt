package com.flowbit.auth.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/** Argon2 is CPU-heavy by design, so every call runs off the event loop. */
@Component
class PasswordHasher(
    private val passwordEncoder: PasswordEncoder,
) {
    /** Hash of a throwaway value, compared against when an account does not exist so timing stays the same. */
    private val decoyHash: String = checkNotNull(passwordEncoder.encode("flowbit-decoy-password"))

    suspend fun hash(rawPassword: String): String = withContext(Dispatchers.Default) { checkNotNull(passwordEncoder.encode(rawPassword)) }

    suspend fun matches(
        rawPassword: String,
        hash: String,
    ): Boolean = withContext(Dispatchers.Default) { passwordEncoder.matches(rawPassword, hash) }

    suspend fun burnTime(rawPassword: String) {
        matches(rawPassword, decoyHash)
    }
}
