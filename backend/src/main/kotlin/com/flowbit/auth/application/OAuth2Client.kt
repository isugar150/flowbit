package com.flowbit.auth.application

import com.flowbit.auth.domain.SsoSettings
import com.flowbit.auth.domain.TokenAuth
import com.flowbit.auth.domain.TokenFormat
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.time.Duration

/**
 * The code exchange for providers that are not OpenID Connect: plain OAuth 2.0 (a token, then a
 * user-info call) and in-house providers whose token address answers with the profile itself.
 * Nothing here is signed by the provider, so the profile is trusted only because it came straight
 * from the configured address over the server's own connection.
 */
@Component
class OAuth2Client(
    private val jsonMapper: JsonMapper,
) {
    private val http = WebClient.create()

    /** Trades the authorization code for the profile of the person who signed in. Not retried: a code works once. */
    suspend fun profile(
        settings: SsoSettings,
        code: String,
        redirectUri: String,
    ): Map<String, Any?> {
        val json = settings.tokenFormat == TokenFormat.JSON
        val tokens =
            attempt("The code exchange failed") {
                http
                    .post()
                    .uri(settings.tokenUrl)
                    .contentType(if (json) MediaType.APPLICATION_JSON else MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers {
                        when (settings.tokenAuth) {
                            TokenAuth.BASIC -> it.setBasicAuth(formEncode(settings.clientId), formEncode(settings.clientSecret))
                            TokenAuth.BEARER -> it.setBearerAuth(settings.clientSecret)
                            TokenAuth.BODY -> {}
                        }
                    }.bodyValue(body(settings, code, redirectUri, json))
                    .retrieve()
                    .bodyToMono(JSON_OBJECT)
                    .timeout(TIMEOUT)
                    .awaitSingle()
            }
        if (settings.userinfoUrl.isEmpty()) return tokens
        val accessToken = tokens["access_token"] as? String ?: throw OidcException("The token response has no access_token")
        return attempt("Could not read the user info") {
            http
                .get()
                .uri(settings.userinfoUrl)
                .accept(MediaType.APPLICATION_JSON)
                .headers { it.setBearerAuth(accessToken) }
                .retrieve()
                .bodyToMono(JSON_OBJECT)
                .timeout(TIMEOUT)
                .awaitSingle()
        }
    }

    private fun body(
        settings: SsoSettings,
        code: String,
        redirectUri: String,
        json: Boolean,
    ): String {
        val escape: (String) -> String =
            if (json) {
                { jsonMapper.writeValueAsString(it).removeSurrounding("\"") }
            } else {
                ::formEncode
            }
        if (settings.tokenBody.isNotBlank()) {
            val values = mapOf("clientId" to settings.clientId, "clientSecret" to settings.clientSecret, "code" to code, "redirectUri" to redirectUri)
            return PLACEHOLDER.replace(settings.tokenBody) { match -> values[match.groupValues[1]]?.let(escape).orEmpty() }
        }
        // The standard OAuth 2.0 request (RFC 6749, 4.1.3).
        val parameters =
            buildMap {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", redirectUri)
                put("client_id", settings.clientId)
                if (settings.tokenAuth == TokenAuth.BODY) put("client_secret", settings.clientSecret)
            }
        return if (json) jsonMapper.writeValueAsString(parameters) else parameters.entries.joinToString("&") { "${it.key}=${formEncode(it.value)}" }
    }

    private suspend fun <T> attempt(
        failure: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (ex: Exception) {
            throw OidcException("$failure: ${ex.message}", ex)
        }

    private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private val JSON_OBJECT = object : ParameterizedTypeReference<Map<String, Any?>>() {}
        private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z]+)\\s*}}")

        /** The value at [path] in [profile]; a dot goes into a nested object. Numbers and booleans become text. */
        fun valueAt(
            profile: Map<String, Any?>,
            path: String,
        ): String? {
            var current: Any? = profile
            for (name in path.split('.')) {
                current = (current as? Map<*, *>)?.get(name) ?: return null
            }
            return when (current) {
                is String -> current.trim()
                is Number, is Boolean -> current.toString()
                else -> null
            }?.takeIf { it.isNotEmpty() }
        }
    }
}
