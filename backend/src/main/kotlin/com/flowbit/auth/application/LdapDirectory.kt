package com.flowbit.auth.application

import com.flowbit.auth.domain.LdapSettings
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest
import com.unboundid.util.ssl.HostNameSSLSocketVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/** The directory could not be used: unreachable, refused the search account, or misconfigured. */
class LdapUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Looks people up in the LDAP directory and checks their password by binding as them.
 * The LDAP SDK blocks, so everything here runs on [Dispatchers.IO], never on an event loop.
 * Certificates of `ldaps://` and StartTLS servers are checked against the JVM's trust store.
 */
@Component
class LdapDirectory {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The person behind [identifier] when [password] is theirs, otherwise null. An unreachable or
     * misconfigured directory also gives null: to someone signing in it is one more failed attempt.
     */
    suspend fun authenticate(
        settings: LdapSettings,
        identifier: String,
        password: String,
    ): ExternalIdentity? {
        // A bind with a DN and an empty password "succeeds" as an anonymous bind on many servers.
        if (password.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                // One connection for both steps: bind as the search account, find the person, then bind
                // as them. Some directories tie the second bind to the service account of the first
                // and refuse a user bind that arrives on a connection of its own.
                searchConnection(settings).use { connection ->
                    val entry = find(connection, settings, identifier) ?: return@use null
                    if (bindsAs(connection, entry.dn, password)) identityOf(settings, entry) else null
                }
            } catch (ex: LdapUnavailableException) {
                log.warn("LDAP sign-in could not reach the directory: {}", ex.message)
                null
            }
        }
    }

    /**
     * Connects with the search account and, when [identifier] is given, looks that person up.
     * Throws [LdapUnavailableException] with a message an administrator can act on.
     */
    suspend fun probe(
        settings: LdapSettings,
        identifier: String?,
    ): ExternalIdentity? =
        withContext(Dispatchers.IO) {
            if (identifier == null) {
                searchConnection(settings).use { connection ->
                    if (unavailableOnError { connection.getEntry(settings.baseDn, "1.1") } == null) {
                        throw LdapUnavailableException("기준 DN 을 찾을 수 없습니다")
                    }
                }
                null
            } else {
                val entry = searchConnection(settings).use { find(it, settings, identifier) } ?: return@withContext null
                identityOf(settings, entry) ?: throw LdapUnavailableException("사용자를 찾았지만 아이디 속성(${settings.loginIdAttribute})에 값이 없습니다")
            }
        }

    /** The single entry the user filter matches, or null when it matches none or more than one. */
    private fun find(
        connection: LDAPConnection,
        settings: LdapSettings,
        identifier: String,
    ): SearchResultEntry? {
        val filter = unavailableOnError { Filter.create(settings.userFilter.replace(PLACEHOLDER, Filter.encodeValue(identifier))) }
        val request =
            SearchRequest(
                settings.baseDn,
                SearchScope.SUB,
                filter,
                settings.loginIdAttribute,
                settings.emailAttribute,
                settings.displayNameAttribute,
            )
        // Two is enough to tell "exactly one" from "ambiguous".
        request.sizeLimit = 2
        val entries =
            try {
                connection.search(request).searchEntries
            } catch (ex: LDAPException) {
                if (ex.resultCode != ResultCode.SIZE_LIMIT_EXCEEDED) throw unavailable(ex)
                return null
            }
        return entries.singleOrNull()
    }

    /** Whether [password] is the password of [dn], checked by binding as them on [connection]. */
    private fun bindsAs(
        connection: LDAPConnection,
        dn: String,
        password: String,
    ): Boolean =
        try {
            connection.bind(dn, password)
            true
        } catch (ex: LDAPException) {
            if (ex.resultCode != ResultCode.INVALID_CREDENTIALS) throw unavailable(ex)
            // Directories that lock an account after repeated failures still answer "invalid
            // credentials"; the reason, when they give one, is only in the diagnostic message.
            ex.diagnosticMessage?.takeIf { it.isNotBlank() }?.let { log.info("LDAP refused the sign-in of {}: {}", dn, it) }
            false
        }

    private fun identityOf(
        settings: LdapSettings,
        entry: SearchResultEntry,
    ): ExternalIdentity? {
        val loginId = entry.getAttributeValue(settings.loginIdAttribute)?.takeIf { it.isNotBlank() } ?: return null
        return ExternalIdentity(
            externalId = null,
            loginId = loginId,
            email = entry.getAttributeValue(settings.emailAttribute),
            displayName = entry.getAttributeValue(settings.displayNameAttribute),
        )
    }

    /** A connection bound as the search account (or anonymous when none is configured). */
    private fun searchConnection(settings: LdapSettings): LDAPConnection {
        val connection = open(settings)
        if (settings.bindDn.isNotBlank()) {
            try {
                connection.bind(settings.bindDn, settings.bindPassword)
            } catch (ex: LDAPException) {
                connection.close()
                throw if (ex.resultCode == ResultCode.INVALID_CREDENTIALS) {
                    LdapUnavailableException("검색 계정의 DN 또는 비밀번호가 올바르지 않습니다", ex)
                } else {
                    unavailable(ex)
                }
            }
        }
        return connection
    }

    private fun open(settings: LdapSettings): LDAPConnection {
        val endpoint = LdapEndpoint.parse(settings.url) ?: throw LdapUnavailableException("서버 주소가 올바르지 않습니다")
        val options =
            LDAPConnectionOptions().apply {
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                responseTimeoutMillis = RESPONSE_TIMEOUT_MILLIS
                sslSocketVerifier = HostNameSSLSocketVerifier(true)
            }
        try {
            if (endpoint.secure) {
                return LDAPConnection(SSLSocketFactory.getDefault(), options, endpoint.host, endpoint.port)
            }
            val connection = LDAPConnection(options, endpoint.host, endpoint.port)
            if (settings.startTls) {
                try {
                    val result = connection.processExtendedOperation(StartTLSExtendedRequest(SSLContext.getDefault()))
                    if (result.resultCode != ResultCode.SUCCESS) throw LDAPException(result)
                } catch (ex: LDAPException) {
                    connection.close()
                    throw ex
                }
            }
            return connection
        } catch (ex: LDAPException) {
            throw unavailable(ex)
        }
    }

    private fun <T> unavailableOnError(block: () -> T): T =
        try {
            block()
        } catch (ex: LDAPException) {
            throw unavailable(ex)
        }

    private fun unavailable(ex: LDAPException) =
        LdapUnavailableException("LDAP 서버 오류: ${ex.resultCode.name} ${ex.diagnosticMessage ?: ex.message.orEmpty()}".trim(), ex)

    companion object {
        const val PLACEHOLDER = "{0}"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val RESPONSE_TIMEOUT_MILLIS = 5_000L
    }
}

/** Host and port of an `ldap://` or `ldaps://` address. */
data class LdapEndpoint(
    val host: String,
    val port: Int,
    val secure: Boolean,
) {
    companion object {
        private const val LDAP_PORT = 389
        private const val LDAPS_PORT = 636

        fun parse(url: String): LdapEndpoint? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val secure =
                when (uri.scheme?.lowercase()) {
                    "ldap" -> false
                    "ldaps" -> true
                    else -> return null
                }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            return LdapEndpoint(
                host,
                if (uri.port > 0) {
                    uri.port
                } else if (secure) {
                    LDAPS_PORT
                } else {
                    LDAP_PORT
                },
                secure,
            )
        }
    }
}
