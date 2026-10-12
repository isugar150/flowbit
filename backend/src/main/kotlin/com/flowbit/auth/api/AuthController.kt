package com.flowbit.auth.api

import com.flowbit.auth.application.AuthProperties
import com.flowbit.auth.application.AuthService
import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.auth.application.LoginResult
import com.flowbit.common.error.UnauthenticatedException
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.api.MeResponse
import com.flowbit.user.application.UserService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

data class LoginRequest(
    val loginId: String,
    val password: String,
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val userService: UserService,
    private val sessionCookies: SessionCookies,
    private val rateLimiter: RateLimiter,
    private val properties: AuthProperties,
) {
    /** Subscribing to the token is what makes Spring Security send the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun csrf(exchange: ServerWebExchange) {
        exchange.getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name)?.awaitSingleOrNull()
    }

    @PostMapping("/login")
    suspend fun login(
        @RequestBody body: LoginRequest,
        exchange: ServerWebExchange,
    ): MeResponse {
        val client = ClientInfo.from(exchange)
        rateLimiter.check("login", client.ipAddress, properties.rateLimitPerMinute)
        return when (val result = authService.login(body.loginId, body.password, client, sessionCookies.read(exchange))) {
            is LoginResult.Success -> {
                sessionCookies.write(exchange, result.sessionToken)
                MeResponse.from(userService.profileOf(result.user))
            }

            LoginResult.Rejected -> {
                throw UnauthenticatedException("INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다")
            }
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun logout(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        exchange: ServerWebExchange,
    ) {
        authService.logout(principal, ClientInfo.from(exchange))
        sessionCookies.clear(exchange)
    }
}
