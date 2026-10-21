package com.flowbit.common.transaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

/**
 * Does something only once the database change it belongs to is safe (D-172). Taking a file out of
 * the storage inside a `@Transactional` function used to happen before the commit: if something
 * failed after it and the change was rolled back, the database still pointed at a file that was
 * gone. Now the file goes after the commit; when the change is rolled back it stays.
 *
 * A failure of [run]'s action is logged and does not reach the caller: the change is already
 * committed and nothing can be undone. A file left behind by it (or by a server that stopped in
 * between) is found by the administrator's file page (D-171).
 */
@Component
class AfterCommit {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Runs [action] after the transaction this is called in commits; at once when there is none; never when it rolls back. */
    suspend fun run(action: suspend () -> Unit) {
        val manager =
            try {
                TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
            } catch (_: NoTransactionException) {
                null
            }
        if (manager == null || !manager.isSynchronizationActive) {
            quietly(action)
            return
        }
        manager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit(): Mono<Void> = mono { quietly(action) }.then()
            },
        )
    }

    private suspend fun quietly(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Something to be done after the commit failed", e)
        }
    }
}
