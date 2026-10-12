package com.flowbit.auth.application

import com.flowbit.auth.domain.SsoSettings
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The provider could not be used, or what it sent back cannot be trusted. Never shown to the person signing in. */
class OidcException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** What the provider publishes about itself at `/.well-known/openid-configuration`. */
class OidcProvider(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val userinfoEndpoint: String?,
    /** Whether the token endpoint takes the client secret in the form body instead of HTTP Basic. */
    val secretInBody: Boolean,
    val idTokenDecoder: NimbusReactiveJwtDecoder,
    val loadedAt: Instant,
)

/**
 * The OpenID Connect calls behind single sign-on: discovery, the code-for-token exchange and the
 * check of the ID token (signature, issuer, audience, expiry, nonce).
 */
@Component
class OidcClient(
    private val clock: Clock,
) {
    private val http = WebClient.create()
    private val providers = ConcurrentHashMap<String, OidcProvider>()

    /** Reads the provider's configuration document, remembered for an hour per issuer. */
    suspend fun provider(rawIssuer: String): OidcProvider {
        val issuer = rawIssuer.trim().trimEnd('/')
        val now = clock.instant()
        providers[issuer]?.takeIf { Duration.between(it.loadedAt, now).abs() < CACHE_TIME }?.let { return it }

        val document = getJson("$issuer/.well-known/openid-configuration", null)
        val published = document.string("issuer") ?: throw OidcException("The configuration document has no issuer")
        // The issuer in the document must be the one that was asked for (OpenID Connect Discovery 4.3).
        if (published.trimEnd('/') != issuer) throw OidcException("The configuration document belongs to another issuer")
        val authMethods = (document["token_endpoint_auth_methods_supported"] as? List<*>)?.filterIsInstance<String>()
        val provider =
            OidcProvider(
                issuer = published,
                authorizationEndpoint = document.required("authorization_endpoint"),
                tokenEndpoint = document.required("token_endpoint"),
                userinfoEndpoint = document.string("userinfo_endpoint"),
                secretInBody = authMethods != null && "client_secret_basic" !in authMethods && "client_secret_post" in authMethods,
                idTokenDecoder =
                    NimbusReactiveJwtDecoder
                        .withJwkSetUri(document.required("jwks_uri"))
                        .jwsAlgorithms { it.addAll(ID_TOKEN_ALGORITHMS) }
                        .build()
                        .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(published)) },
                loadedAt = now,
            )
        providers[issuer] = provider
        return provider
    }

    /**
     * Trades the authorization code for the claims of the person who signed in. The claims come
     * from the verified ID token; what it lacks is filled in from the userinfo endpoint.
     */
    suspend fun claims(
        settings: SsoSettings,
        provider: OidcProvider,
        code: String,
        redirectUri: String,
        codeVerifier: String,
        nonce: String,
    ): Map<String, Any?> {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("code", code)
                add("redirect_uri", redirectUri)
                add("code_verifier", codeVerifier)
                if (provider.secretInBody) {
                    add("client_id", settings.clientId)
                    add("client_secret", settings.clientSecret)
                }
            }
        val tokens =
            attempt("The token request failed") {
                http
                    .post()
                    .uri(provider.tokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers { if (!provider.secretInBody) it.setBasicAuth(formEncode(settings.clientId), formEncode(settings.clientSecret)) }
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(JSON_OBJECT)
                    .timeout(TIMEOUT)
                    .awaitSingle()
            }
        val idToken = tokens.string("id_token") ?: throw OidcException("The token response has no id_token")
        val jwt =
            try {
                provider.idTokenDecoder.decode(idToken).awaitSingle()
            } catch (ex: JwtException) {
                throw OidcException("The ID token was rejected: ${ex.message}", ex)
            }
        if (settings.clientId !in jwt.audience.orEmpty()) throw OidcException("The ID token is for another client")
        if (jwt.getClaimAsString("nonce") != nonce) throw OidcException("The ID token does not belong to this sign-in")

        val claims: MutableMap<String, Any?> = jwt.claims.toMutableMap()
        val accessToken = tokens.string("access_token")
        if (provider.userinfoEndpoint != null && accessToken != null) {
            // Optional extra detail: a failure here must not fail a sign-in the ID token already proved.
            val userinfo = runCatching { getJson(provider.userinfoEndpoint, accessToken) }.getOrNull()
            // Userinfo that names someone else must be ignored (OpenID Connect Core 5.3.2).
            if (userinfo != null && userinfo["sub"] == jwt.subject) {
                userinfo.forEach { (name, value) -> if (name !in claims) claims[name] = value }
            }
        }
        return claims
    }

    private suspend fun getJson(
        uri: String,
        bearerToken: String?,
    ): Map<String, Any?> =
        attempt("Could not read $uri") {
            http
                .get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .headers { if (bearerToken != null) it.setBearerAuth(bearerToken) }
                .retrieve()
                .bodyToMono(JSON_OBJECT)
                .timeout(TIMEOUT)
                .awaitSingle()
        }

    private suspend fun <T> attempt(
        failure: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (ex: OidcException) {
            throw ex
        } catch (ex: Exception) {
            throw OidcException("$failure: ${ex.message}", ex)
        }

    private fun Map<String, Any?>.string(name: String): String? = (this[name] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.required(name: String): String = string(name) ?: throw OidcException("The configuration document has no $name")

    /** Client id and secret are form-encoded before they go into HTTP Basic (RFC 6749, 2.3.1). */
    private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        val CACHE_TIME: Duration = Duration.ofHours(1)
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        val JSON_OBJECT = object : ParameterizedTypeReference<Map<String, Any?>>() {}
        val ID_TOKEN_ALGORITHMS =
            listOf(
                SignatureAlgorithm.RS256,
                SignatureAlgorithm.RS384,
                SignatureAlgorithm.RS512,
                SignatureAlgorithm.PS256,
                SignatureAlgorithm.ES256,
                SignatureAlgorithm.ES384,
            )
    }
}
