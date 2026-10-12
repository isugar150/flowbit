package com.flowbit.auth.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("flowbit.auth")
data class AuthProperties(
    /** Consecutive wrong passwords before an account is locked. */
    val maxFailedLogins: Int = 5,
    val lockDuration: Duration = Duration.ofMinutes(15),
    /** A session ends when it has not been used for this long. */
    val sessionIdleTimeout: Duration = Duration.ofDays(7),
    /** A session ends this long after sign-in, however recently it was used. */
    val sessionMaxAge: Duration = Duration.ofDays(30),
    /** Login and setup attempts allowed per IP address per minute. */
    val rateLimitPerMinute: Int = 10,
)
