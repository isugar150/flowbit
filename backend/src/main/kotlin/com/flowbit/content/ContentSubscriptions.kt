package com.flowbit.content

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.NotFoundException
import com.flowbit.issue.application.IssuePerson
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID

/** Who follows a document, and whether the person looking does. */
data class ContentSubscription(
    val subscribers: List<IssuePerson>,
    val subscribed: Boolean,
)

/** The people who follow documents (`document_subscriptions`, D-166). */
@Component
class DocumentSubscriptionStore(
    private val databaseClient: DatabaseClient,
) {
    /** Following twice is the same as once. */
    suspend fun subscribe(
        documentId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("insert into document_subscriptions (document_id, user_id) values (:documentId, :userId) on conflict do nothing")
            .bind("documentId", documentId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun unsubscribe(
        documentId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("delete from document_subscriptions where document_id = :documentId and user_id = :userId")
            .bind("documentId", documentId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Active people who follow the document, in the order they began. */
    suspend fun subscribers(documentId: UUID): List<IssuePerson> =
        databaseClient
            .sql(
                """
                select u.id, u.login_id, u.display_name, u.avatar_key
                from document_subscriptions s join users u on u.id = s.user_id
                where s.document_id = :documentId and u.status = 'ACTIVE'
                order by s.created_at, u.login_id
                """.trimIndent(),
            ).bind("documentId", documentId)
            .map { row ->
                IssuePerson(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    loginId = checkNotNull(row.get("login_id", String::class.java)),
                    displayName = row.get("display_name", String::class.java),
                    avatarKey = row.get("avatar_key", String::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()
}

/**
 * Following a document (D-166). Anyone who may see the document, the view-only role included, may
 * follow it or stop; it is also what a tag in a comment on it does, which the person tagged can
 * undo.
 */
@Service
class ContentSubscriptionService(
    private val access: ContentAccess,
    private val store: DocumentSubscriptionStore,
) {
    /** Null for a page that is not followed on its own: a project (which has its own button), a milestone, an issue. */
    suspend fun of(
        user: AuthenticatedUser,
        context: ContentContext,
    ): ContentSubscription? {
        if (context.target.type != ContentTargetType.DOCUMENT) return null
        val subscribers = store.subscribers(context.target.id)
        return ContentSubscription(subscribers, subscribers.any { it.id == user.id })
    }

    suspend fun set(
        user: AuthenticatedUser,
        target: ContentTarget,
        subscribed: Boolean,
    ): Pair<ContentContext, ContentSubscription> {
        if (target.type != ContentTargetType.DOCUMENT) throw NotFoundException("CONTENT_NOT_FOUND", "찾을 수 없습니다")
        val context = access.resolve(user, target)
        if (subscribed) store.subscribe(target.id, user.id) else store.unsubscribe(target.id, user.id)
        return context to checkNotNull(of(user, context))
    }
}
