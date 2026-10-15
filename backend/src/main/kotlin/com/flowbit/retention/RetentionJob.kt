package com.flowbit.retention

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Runs [RetentionService.purge] in the background: [initialDelay] after the server is up, then
 * every [interval]. A failed run is logged and the next one tries again. Integration tests switch
 * it off and call the service themselves.
 */
@Component
class RetentionJob(
    private val service: RetentionService,
    @Value("\${flowbit.retention.enabled:true}") private val enabled: Boolean,
    @Value("\${flowbit.retention.initial-delay:1m}") private val initialDelay: Duration,
    @Value("\${flowbit.retention.interval:1h}") private val interval: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) return
        scope.launch {
            delay(initialDelay.toMillis())
            while (isActive) {
                try {
                    service.purge()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Removing old deleted data failed; trying again in {}", interval, e)
                }
                delay(interval.toMillis())
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }
}
