package com.flowbit.auth.api

import com.flowbit.auth.application.AuthProperties
import com.flowbit.auth.application.ExternalAuthSettingsService
import com.flowbit.auth.application.SsoAttempt
import com.flowbit.auth.application.SsoResult
import com.flowbit.auth.application.SsoService
import com.flowbit.auth.domain.SsoButtonStyle
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.common.web.ClientInfo
import com.flowbit.common.web.PublicUrl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/** Which ways of signing in the sign-in page should offer. Public. */
data class AuthMethodsResponse(
    val ldap: Boolean,
    val sso: SsoMethod?,
) {
    data class SsoMethod(
        val displayName: String,
        val buttonStyle: SsoButtonStyle,
    )
}

/** Where the identity provider sends people back to; it must be registered there exactly. */
@Component
class SsoRedirectUri(
    private val publicUrl: PublicUrl,
) {
    fun of(exchange: ServerWebExchange): String {
        publicUrl.base?.let { return it + CALLBACK_PATH }
        // The request URI already reflects X-Forwarded-* (server.forward-headers-strategy).
        return UriComponentsBuilder
            .fromUri(exchange.request.uri)
            .replacePath(CALLBACK_PATH)
            .replaceQuery(null)
            .fragment(null)
            .build()
            .toUriString()
    }

    private companion object {
        const val CALLBACK_PATH = "/api/v1/auth/sso/callback"
    }
}

/**
 * The two browser redirects of single sign-on. These are page navigations, not JSON calls: they
 * always answer with a redirect, and a failure goes back to the sign-in page as `/login?error=…`.
 */
@RestController
@RequestMapping("/api/v1/auth")
class SsoController(
    private val ssoService: SsoService,
    private val settingsService: ExternalAuthSettingsService,
    private val redirectUri: SsoRedirectUri,
    private val sessionCookies: SessionCookies,
    private val rateLimiter: RateLimiter,
    private val properties: AuthProperties,
) {
    @GetMapping("/methods")
    suspend fun methods(): AuthMethodsResponse {
        val sso = settingsService.sso()
        return AuthMethodsResponse(
            ldap = settingsService.ldap().enabled,
            sso = if (sso.enabled) AuthMethodsResponse.SsoMethod(sso.displayName, sso.buttonStyle) else null,
        )
    }

    @GetMapping("/sso/start")
    suspend fun start(
        @RequestParam(required = false) next: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Void> {
        val start = ssoService.start(redirectUri.of(exchange)) ?: return redirect(failureLocation(SsoService.FAILED))
        val attempt = start.attempt
        val value = listOf(attempt.state, attempt.nonce, attempt.codeVerifier, ENCODER.encodeToString(safeNext(next).toByteArray())).joinToString(".")
        exchange.response.addCookie(attemptCookie(exchange, value, ATTEMPT_LIFETIME))
        return redirect(start.authorizationUrl)
    }

    @GetMapping("/sso/callback")
    suspend fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Void> {
        val client = ClientInfo.from(exchange)
        rateLimiter.check("login", client.ipAddress, properties.rateLimitPerMinute)
        val parts =
            exchange.request.cookies
                .getFirst(ATTEMPT_COOKIE)
                ?.value
                ?.split(".")
        // One use only, whatever happens next.
        exchange.response.addCookie(attemptCookie(exchange, "", Duration.ZERO))
        // No cookie or a state that is not ours: this is not the answer to a sign-in started here.
        if (parts?.size != ATTEMPT_PARTS || state == null || !sameText(parts[0], state)) {
            return redirect(failureLocation(SsoService.FAILED))
        }
        // The provider turned the person away (not allowed to use this service, or they cancelled).
        if (error == "access_denied") return redirect(failureLocation(SsoService.DENIED))
        if (code.isNullOrEmpty()) return redirect(failureLocation(SsoService.FAILED))
        val attempt = SsoAttempt(parts[0], parts[1], parts[2])
        val next = runCatching { String(Base64.getUrlDecoder().decode(parts[3])) }.getOrDefault("/")
        return when (val result = ssoService.complete(attempt, code, redirectUri.of(exchange), client, sessionCookies.read(exchange))) {
            is SsoResult.Success -> {
                sessionCookies.write(exchange, result.sessionToken)
                redirect(safeNext(next))
            }

            is SsoResult.Failed -> {
                redirect(failureLocation(result.code))
            }
        }
    }

    private fun redirect(location: String): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build()

    private fun failureLocation(code: String) = "/login?error=$code"

    private fun attemptCookie(
        exchange: ServerWebExchange,
        value: String,
        maxAge: Duration,
    ): ResponseCookie =
        ResponseCookie
            .from(ATTEMPT_COOKIE, value)
            .httpOnly(true)
            // Lax: the cookie must come back on the top-level redirect from the identity provider.
            .sameSite("Lax")
            .secure(exchange.request.uri.scheme == "https")
            .path("/api/v1/auth/sso")
            .maxAge(maxAge)
            .build()

    private fun sameText(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val ATTEMPT_COOKIE = "FLOWBIT_SSO"
        private const val ATTEMPT_PARTS = 4
        private val ATTEMPT_LIFETIME: Duration = Duration.ofMinutes(10)
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val SAFE_PATH = Regex("^/[A-Za-z0-9\\-._~!$&'()*+,;=:@%/?]*$")

        /** Only a path on this site may follow a sign-in; anything else becomes the home page. */
        fun safeNext(next: String?): String = if (next != null && !next.startsWith("//") && SAFE_PATH.matches(next)) next else "/"
    }
}
