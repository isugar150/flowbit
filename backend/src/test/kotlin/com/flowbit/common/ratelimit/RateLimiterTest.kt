package com.flowbit.common.ratelimit

import com.flowbit.common.error.TooManyRequestsException
import com.flowbit.support.MutableClock
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimiterTest {
    private val clock = MutableClock()
    private val limiter = RateLimiter(clock)

    @Test
    fun `한도까지는 허용하고 넘으면 남은 시간과 함께 거부한다`() {
        repeat(3) { limiter.check("login", "10.0.0.1", limit = 3) }

        val error = assertFailsWith<TooManyRequestsException> { limiter.check("login", "10.0.0.1", limit = 3) }

        val retryAfter = checkNotNull(error.retryAfter)
        assertTrue(retryAfter > Duration.ZERO && retryAfter <= Duration.ofMinutes(1))
    }

    @Test
    fun `키와 버킷이 다르면 따로 센다`() {
        repeat(3) { limiter.check("login", "10.0.0.1", limit = 3) }

        limiter.check("login", "10.0.0.2", limit = 3)
        limiter.check("setup", "10.0.0.1", limit = 3)
    }

    @Test
    fun `시간 창이 지나면 다시 허용한다`() {
        repeat(3) { limiter.check("login", "10.0.0.1", limit = 3) }

        clock.advance(Duration.ofMinutes(1))

        limiter.check("login", "10.0.0.1", limit = 3)
    }
}
