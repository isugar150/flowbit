package com.flowbit.mcp

import com.flowbit.common.error.ProblemResponseWriter
import com.flowbit.common.error.TooManyRequestsException
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.common.web.ClientInfo
import com.flowbit.common.web.PublicUrl
import kotlinx.coroutines.reactor.mono
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.ServerSecurityContextRepository
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI

const val MCP_PATH = "/api/v1/mcp"
const val MCP_IDENTITY = "flowbit.mcp.identity"

@Configuration(proxyBeanMethods = false)
class McpSecurity {
    @Bean
    @Order(-1)
    fun mcpSecurityWebFilterChain(
        http: ServerHttpSecurity,
        tokens: AccessTokens,
        problems: ProblemResponseWriter,
        publicUrl: PublicUrl,
        limiter: RateLimiter,
    ): SecurityWebFilterChain {
        val repository =
            object : ServerSecurityContextRepository {
                override fun save(
                    exchange: ServerWebExchange,
                    context: SecurityContext?,
                ): Mono<Void> = Mono.empty()

                override fun load(exchange: ServerWebExchange): Mono<SecurityContext> =
                    mono {
                        val origin = exchange.request.headers.origin
                        val expected = publicUrl.base ?: "${exchange.request.uri.scheme}://${exchange.request.uri.rawAuthority}"
                        if (origin != null && !sameOrigin(origin, expected)) return@mono null
                        try {
                            limiter.check("mcp-auth", ClientInfo.from(exchange).ipAddress, 600)
                        } catch (failure: TooManyRequestsException) {
                            exchange.attributes["flowbit.mcp.rateLimit"] = failure
                            return@mono null
                        }
                        val header = exchange.request.headers.getFirst("Authorization") ?: return@mono null
                        if (!header.startsWith("Bearer ", ignoreCase = true)) return@mono null
                        val identity = tokens.resolve(header.substring(7)) ?: return@mono null
                        exchange.attributes[MCP_IDENTITY] = identity
                        SecurityContextImpl(
                            UsernamePasswordAuthenticationToken.authenticated(
                                identity.user,
                                null,
                                listOf(SimpleGrantedAuthority("ROLE_${identity.user.role.name}")),
                            ),
                        )
                    }
            }
        return http
            .securityMatcher(ServerWebExchangeMatchers.pathMatchers(MCP_PATH, "/api/v1/integrations/mcp/**"))
            // This chain accepts only explicit bearer credentials, never browser cookies.
            .csrf { it.disable() }
            .securityContextRepository(repository)
            .authorizeExchange { it.anyExchange().authenticated() }
            .exceptionHandling {
                it.authenticationEntryPoint { exchange, _ ->
                    val invalidOrigin =
                        exchange.request.headers.origin?.let { origin ->
                            !sameOrigin(
                                origin,
                                publicUrl.base ?: "${exchange.request.uri.scheme}://${exchange.request.uri.rawAuthority}",
                            )
                        } ==
                            true
                    val limited = exchange.getAttribute<TooManyRequestsException>("flowbit.mcp.rateLimit")
                    if (limited != null) {
                        exchange.response.headers.set(
                            "Retry-After",
                            limited.retryAfter
                                ?.seconds
                                ?.coerceAtLeast(1)
                                .toString(),
                        )
                        problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, limited.code, "요청이 너무 많습니다. 잠시 후 다시 시도하세요")
                    } else {
                        exchange.response.headers.set("WWW-Authenticate", "Bearer realm=\"Flowbit MCP\"")
                        problems.write(
                            exchange,
                            if (invalidOrigin) HttpStatus.FORBIDDEN else HttpStatus.UNAUTHORIZED,
                            if (invalidOrigin) "FORBIDDEN" else "UNAUTHENTICATED",
                            "MCP 주소와 개인 토큰을 확인하세요",
                        )
                    }
                }
            }.requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .build()
    }

    private fun sameOrigin(
        origin: String,
        expected: String,
    ): Boolean =
        runCatching {
            val actual = URI(origin)
            val target = URI(expected)

            fun port(uri: URI) =
                if (uri.port >= 0) {
                    uri.port
                } else if (uri.scheme == "https") {
                    443
                } else {
                    80
                }
            actual.host != null &&
                actual.userInfo == null &&
                actual.scheme.equals(target.scheme, true) &&
                actual.host.equals(target.host, true) &&
                port(actual) == port(target) &&
                actual.path.orEmpty() in listOf("", "/") &&
                actual.query == null &&
                actual.fragment == null
        }.getOrDefault(false)
}
