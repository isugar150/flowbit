package com.flowbit.auth.application

import com.flowbit.auth.domain.SsoProtocol
import com.flowbit.auth.domain.SsoSettings
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.domain.AuthSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What the browser must bring back from the provider for the sign-in to be completed. */
data class SsoAttempt(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
)

data class SsoStart(
    val authorizationUrl: String,
    val attempt: SsoAttempt,
)

sealed interface SsoResult {
    data class Success(
        val sessionToken: String,
    ) : SsoResult

    /** [code] is what the sign-in page is told (`/login?error=…`); details go to the log only. */
    data class Failed(
        val code: String,
    ) : SsoResult
}

/**
 * Single sign-on with OpenID Connect: authorization code flow with PKCE, state and nonce
 * (docs/features/M2b-ldap-and-sso.md, "SSO 로그인 흐름").
 */
@Service
class SsoService(
    private val settingsService: ExternalAuthSettingsService,
    private val oidcClient: OidcClient,
    private val oauth2Client: OAuth2Client,
    private val externalAccounts: ExternalAccountService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /** Null when single sign-on is switched off or the provider cannot be reached. */
    suspend fun start(redirectUri: String): SsoStart? {
        val settings = settingsService.sso()
        if (!settings.enabled) return null
        val attempt = SsoAttempt(token(), token(), token())
        val url =
            when (settings.protocol) {
                SsoProtocol.OIDC -> {
                    val provider =
                        try {
                            oidcClient.provider(settings.issuer)
                        } catch (ex: OidcException) {
                            log.warn("Single sign-on could not start: {}", ex.message)
                            return null
                        }
                    authorizeUrl(provider.authorizationEndpoint, settings, redirectUri, attempt.state)
                        .queryParam("nonce", attempt.nonce)
                        .queryParam("code_challenge", challengeOf(attempt.codeVerifier))
                        .queryParam("code_challenge_method", "S256")
                }

                // Only what plain OAuth 2.0 defines: a provider of its own making may reject parameters it does not know.
                SsoProtocol.OAUTH2 -> {
                    authorizeUrl(settings.authorizeUrl, settings, redirectUri, attempt.state)
                }
            }.encode().build().toUriString()
        return SsoStart(url, attempt)
    }

    suspend fun complete(
        attempt: SsoAttempt,
        code: String,
        redirectUri: String,
        client: ClientInfo,
        previousSessionToken: String?,
    ): SsoResult {
        val settings = settingsService.sso()
        if (!settings.enabled) return SsoResult.Failed(FAILED)
        val identity =
            try {
                when (settings.protocol) {
                    SsoProtocol.OIDC -> {
                        val claims = oidcClient.claims(settings, oidcClient.provider(settings.issuer), code, redirectUri, attempt.codeVerifier, attempt.nonce)
                        identityOf(claims, "sub", settings.loginIdClaim, "email", "name")
                    }

                    SsoProtocol.OAUTH2 -> {
                        val profile = oauth2Client.profile(settings, code, redirectUri)
                        identityOf(profile, settings.subjectField, settings.loginIdClaim, settings.emailField, settings.nameField)
                    }
                }
            } catch (ex: OidcException) {
                log.warn("Single sign-on failed: {}", ex.message)
                return SsoResult.Failed(FAILED)
            }
        if (identity == null) {
            log.warn("Single sign-on failed: the provider sent no subject, or neither '{}' nor an e-mail address", settings.loginIdClaim)
            return SsoResult.Failed("sso_no_login_id")
        }
        return when (val signIn = externalAccounts.signIn(AuthSource.SSO, identity, settings.autoProvision, client, previousSessionToken)) {
            is ExternalSignIn.Success -> SsoResult.Success(signIn.sessionToken)
            is ExternalSignIn.Refused -> SsoResult.Failed("sso_" + signIn.reason.name.lowercase())
        }
    }

    /** Null when the profile does not say who this is. The login id falls back to the e-mail address. */
    private fun identityOf(
        profile: Map<String, Any?>,
        subjectField: String,
        loginIdField: String,
        emailField: String,
        nameField: String,
    ): ExternalIdentity? {
        val subject = OAuth2Client.valueAt(profile, subjectField) ?: return null
        val email = OAuth2Client.valueAt(profile, emailField)
        val loginId = OAuth2Client.valueAt(profile, loginIdField) ?: email ?: return null
        return ExternalIdentity(subject, loginId, email, OAuth2Client.valueAt(profile, nameField))
    }

    private fun authorizeUrl(
        endpoint: String,
        settings: SsoSettings,
        redirectUri: String,
        state: String,
    ): UriComponentsBuilder {
        val builder =
            UriComponentsBuilder
                .fromUriString(endpoint)
                .queryParam("response_type", "code")
                .queryParam("client_id", settings.clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
        if (settings.scopes.isNotEmpty()) builder.queryParam("scope", settings.scopes)
        return builder
    }

    private fun token(): String = ByteArray(TOKEN_BYTES).also(random::nextBytes).let(ENCODER::encodeToString)

    private fun challengeOf(verifier: String): String =
        ENCODER.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    companion object {
        const val FAILED = "sso_failed"
        const val DENIED = "sso_denied"
        private const val TOKEN_BYTES = 32
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    }
}
