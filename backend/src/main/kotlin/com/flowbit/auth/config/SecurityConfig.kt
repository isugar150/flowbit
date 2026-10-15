package com.flowbit.auth.config

import com.flowbit.common.error.ProblemResponseWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.CsrfException
import org.springframework.security.web.server.csrf.CsrfWebFilter
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {
    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        securityContextRepository: SessionSecurityContextRepository,
        problems: ProblemResponseWriter,
    ): SecurityWebFilterChain {
        // Security failures happen before any controller runs, so they are written here in the
        // same problem format GlobalExceptionHandler uses.
        val entryPoint =
            ServerAuthenticationEntryPoint { exchange, _ ->
                problems.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다")
            }
        val accessDeniedHandler =
            ServerAccessDeniedHandler { exchange, ex ->
                if (ex is CsrfException) {
                    problems.write(
                        exchange,
                        HttpStatus.FORBIDDEN,
                        "CSRF_TOKEN_INVALID",
                        "요청을 확인할 수 없습니다. 페이지를 새로 고친 뒤 다시 시도하세요",
                    )
                } else {
                    problems.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "이 작업을 할 권한이 없습니다")
                }
            }
        return http
            // Cookie + header double submit, see docs/06-api-conventions.md "CSRF". The client
            // echoes the XSRF-TOKEN cookie value as-is in X-XSRF-TOKEN.
            .csrf {
                it
                    // Git servers call their webhook address without a browser; the signature is checked instead.
                    .requireCsrfProtectionMatcher(
                        AndServerWebExchangeMatcher(
                            CsrfWebFilter.DEFAULT_CSRF_MATCHER,
                            NegatedServerWebExchangeMatcher(PathPatternParserServerWebExchangeMatcher(GIT_WEBHOOK)),
                        ),
                    ).csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(ServerCsrfTokenRequestAttributeHandler())
                    .accessDeniedHandler(accessDeniedHandler)
            }.securityContextRepository(securityContextRepository)
            .authorizeExchange {
                it
                    .pathMatchers(HttpMethod.GET, *PUBLIC_GET)
                    .permitAll()
                    .pathMatchers(HttpMethod.POST, *PUBLIC_POST)
                    .permitAll()
                    .pathMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyExchange()
                    .authenticated()
            }.exceptionHandling { it.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler) }
            // Sign-in and sign-out are JSON endpoints in AuthController; nothing here may create a WebSession.
            .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder =
        DelegatingPasswordEncoder(
            "argon2",
            mapOf("argon2" to Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()),
        )

    private companion object {
        val PUBLIC_GET =
            arrayOf(
                "/api/v1/health",
                "/api/v1/setup/status",
                "/api/v1/auth/csrf",
                "/api/v1/auth/methods",
                "/api/v1/auth/sso/start",
                "/api/v1/auth/sso/callback",
            )

        /** Called by Git servers, signed with the connection's secret (docs/features/git-integration.md). */
        const val GIT_WEBHOOK = "/api/v1/integrations/git/*/webhook"
        val PUBLIC_POST = arrayOf("/api/v1/setup", "/api/v1/auth/login", GIT_WEBHOOK)
    }
}
