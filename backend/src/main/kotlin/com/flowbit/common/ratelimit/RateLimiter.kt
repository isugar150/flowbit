package com.flowbit.common.ratelimit

import com.flowbit.common.error.TooManyRequestsException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window request counter kept in memory. Enough for a single-instance install;
 * counts reset when the server restarts.
 */
@Component
class RateLimiter(
    private val clock: Clock,
) {
    private data class Window(
        val startedAt: Instant,
        val count: Int,
    )

    private val windows = ConcurrentHashMap<String, Window>()

    /** Counts one request and throws [TooManyRequestsException] once `limit` is exceeded within `window`. */
    fun check(
        bucket: String,
        key: String?,
        limit: Int,
        window: Duration = Duration.ofMinutes(1),
    ) {
        val now = clock.instant()
        if (windows.size > MAX_TRACKED_KEYS) {
            windows.entries.removeIf {
                !it.value.startedAt
                    .plus(window)
                    .isAfter(now)
            }
        }
        val updated =
            checkNotNull(
                windows.compute("$bucket:${key ?: "unknown"}") { _, current ->
                    if (current == null || !current.startedAt.plus(window).isAfter(now)) {
                        Window(now, 1)
                    } else {
                        current.copy(count = current.count + 1)
                    }
                },
            )
        if (updated.count > limit) {
            throw TooManyRequestsException(Duration.between(now, updated.startedAt.plus(window)))
        }
    }

    fun reset() {
        windows.clear()
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 10_000
    }
}
