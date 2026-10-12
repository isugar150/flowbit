package com.flowbit.auth.config

import com.flowbit.auth.api.SessionCookies
import com.flowbit.auth.application.SessionService
import kotlinx.coroutines.reactor.mono
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.server.context.ServerSecurityContextRepository
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Resolves the signed-in user from the session cookie on every request. Nothing is kept in a
 * WebSession: sessions live in the `user_sessions` table and are created by AuthService.
 */
@Component
class SessionSecurityContextRepository(
    private val sessionService: SessionService,
    private val sessionCookies: SessionCookies,
) : ServerSecurityContextRepository {
    override fun save(
        exchange: ServerWebExchange,
        context: SecurityContext?,
    ): Mono<Void> = Mono.empty()

    override fun load(exchange: ServerWebExchange): Mono<SecurityContext> =
        mono {
            val token = sessionCookies.read(exchange) ?: return@mono null
            val user = sessionService.resolve(token) ?: return@mono null
            val authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                    user,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")),
                )
            SecurityContextImpl(authentication)
        }
}
