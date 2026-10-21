package com.flowbit.common.transaction

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A change that is rolled back must not take the file with it (D-172). A transaction manager that does nothing real is enough to see when the action runs. */
class AfterCommitTest {
    private val afterCommit = AfterCommit()
    private val operator = TransactionalOperator.create(NothingManager())
    private val steps = mutableListOf<String>()

    @Test
    fun `커밋된 뒤에 실행된다`() =
        runBlocking {
            operator.executeAndAwait {
                afterCommit.run { steps += "after" }
                steps += "inside"
            }

            assertEquals(listOf("inside", "after"), steps)
        }

    @Test
    fun `롤백되면 실행되지 않는다`() =
        runBlocking {
            assertFailsWith<IllegalStateException> {
                operator.executeAndAwait {
                    afterCommit.run { steps += "after" }
                    throw IllegalStateException("뒤에서 오류")
                }
            }

            assertEquals(emptyList(), steps)
        }

    @Test
    fun `트랜잭션 밖에서는 바로 실행된다`() =
        runBlocking {
            afterCommit.run { steps += "now" }

            assertEquals(listOf("now"), steps)
        }

    @Test
    fun `실행한 일이 실패해도 이미 커밋된 요청은 실패하지 않는다`() =
        runBlocking {
            operator.executeAndAwait {
                afterCommit.run { error("파일을 못 지움") }
                steps += "inside"
            }

            assertEquals(listOf("inside"), steps)
        }

    private class NothingManager : AbstractReactiveTransactionManager() {
        override fun doGetTransaction(synchronizationManager: TransactionSynchronizationManager): Any = Any()

        override fun doBegin(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
            definition: TransactionDefinition,
        ): Mono<Void> = Mono.empty()

        override fun doCommit(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.empty()

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.empty()
    }
}
