package com.flowbit.auth.api

import com.flowbit.auth.application.AuthProperties
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import java.time.Duration

/** Reads and writes the session cookie (docs/06-api-conventions.md, "인증"). */
@Component
class SessionCookies(
    private val properties: AuthProperties,
) {
    fun read(exchange: ServerWebExchange): String? =
        exchange.request.cookies
            .getFirst(NAME)
            ?.value
            ?.takeIf { it.isNotBlank() }

    fun write(
        exchange: ServerWebExchange,
        token: String,
    ) {
        exchange.response.addCookie(cookie(exchange, token, properties.sessionMaxAge))
    }

    fun clear(exchange: ServerWebExchange) {
        exchange.response.addCookie(cookie(exchange, "", Duration.ZERO))
    }

    private fun cookie(
        exchange: ServerWebExchange,
        value: String,
        maxAge: Duration,
    ): ResponseCookie =
        ResponseCookie
            .from(NAME, value)
            .httpOnly(true)
            .sameSite("Lax")
            .secure(exchange.request.uri.scheme == "https")
            .path("/")
            .maxAge(maxAge)
            .build()

    companion object {
        const val NAME = "FLOWBIT_SESSION"
    }
}
