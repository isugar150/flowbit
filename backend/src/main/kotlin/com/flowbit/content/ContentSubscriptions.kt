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
 * The people who follow projects (`project_subscriptions`, D-102): told about every change to the project's issues
 * and, since D-176, about what happens on the project page itself, as a document's followers are about the document.
 */
@Component
class ProjectSubscriptionStore(
    private val databaseClient: DatabaseClient,
) {
    /** Following twice is the same as once. */
    suspend fun subscribe(
        projectId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("insert into project_subscriptions (project_id, user_id) values (:projectId, :userId) on conflict do nothing")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun unsubscribe(
        projectId: UUID,
        userId: UUID,
    ) {
        databaseClient
            .sql("delete from project_subscriptions where project_id = :projectId and user_id = :userId")
            .bind("projectId", projectId)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** Active people who follow the project, in the order they began. */
    suspend fun subscribers(projectId: UUID): List<IssuePerson> =
        databaseClient
            .sql(
                """
                select u.id, u.login_id, u.display_name, u.avatar_key
                from project_subscriptions s join users u on u.id = s.user_id
                where s.project_id = :projectId and u.status = 'ACTIVE'
                order by s.created_at, u.login_id
                """.trimIndent(),
            ).bind("projectId", projectId)
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
 * Following a document (D-166) or a project (D-176). Anyone who may see the page, the view-only role included, may
 * follow it or stop; a tag in a comment on a document does it too, which the person tagged can undo. The same button
 * is on the project page's activity as on a document's, with the avatars of who follows.
 */
@Service
class ContentSubscriptionService(
    private val access: ContentAccess,
    private val documents: DocumentSubscriptionStore,
    private val projects: ProjectSubscriptionStore,
) {
    /** Null for a page that is not followed on its own: an issue. */
    suspend fun of(
        user: AuthenticatedUser,
        context: ContentContext,
    ): ContentSubscription? {
        val subscribers =
            when (context.target.type) {
                ContentTargetType.DOCUMENT -> documents.subscribers(context.target.id)
                ContentTargetType.PROJECT -> projects.subscribers(context.target.id)
                else -> return null
            }
        return ContentSubscription(subscribers, subscribers.any { it.id == user.id })
    }

    suspend fun set(
        user: AuthenticatedUser,
        target: ContentTarget,
        subscribed: Boolean,
    ): Pair<ContentContext, ContentSubscription> {
        if (target.type != ContentTargetType.DOCUMENT && target.type != ContentTargetType.PROJECT) {
            throw NotFoundException("CONTENT_NOT_FOUND", "찾을 수 없습니다")
        }
        val context = access.resolve(user, target)
        when (target.type) {
            ContentTargetType.DOCUMENT -> if (subscribed) documents.subscribe(target.id, user.id) else documents.unsubscribe(target.id, user.id)
            else -> if (subscribed) projects.subscribe(target.id, user.id) else projects.unsubscribe(target.id, user.id)
        }
        return context to checkNotNull(of(user, context))
    }
}
