package com.flowbit.notification

import kotlinx.coroutines.flow.Flow
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.UUID

enum class SmtpSecurity { NONE, STARTTLS, SSL }

/** Mail server and Firebase settings. Exactly one row exists (id = 1). */
@Table("notification_settings")
class NotificationSettings(
    @Id
    val id: Short,
    var smtpEnabled: Boolean,
    var smtpHost: String,
    var smtpPort: Int,
    var smtpSecurity: SmtpSecurity,
    var smtpUsername: String,
    var smtpPassword: String,
    var smtpFromAddress: String,
    var smtpFromName: String,
    var pushEnabled: Boolean,
    /** The service account key file (JSON) the server signs its FCM requests with. */
    var fcmServiceAccount: String,
    var fcmApiKey: String,
    var fcmMessagingSenderId: String,
    var fcmAppId: String,
    var fcmVapidKey: String,
    var updatedAt: Instant,
) {
    companion object {
        const val SINGLETON_ID: Short = 1
    }
}

interface NotificationSettingsRepository : CoroutineCrudRepository<NotificationSettings, Short>

/** A browser that agreed to receive push notifications. */
@Table("push_tokens")
class PushToken(
    @Id
    val token: String,
    val userId: UUID,
    val userAgent: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

interface PushTokenRepository : CoroutineCrudRepository<PushToken, String> {
    @Query("select * from push_tokens where user_id in (:userIds)")
    fun findByUserIds(userIds: Collection<UUID>): Flow<PushToken>

    /** Registers the browser, or hands a token that moved to another account to its new owner. */
    @Modifying
    @Query(
        """
        insert into push_tokens (token, user_id, user_agent, created_at, last_seen_at)
        values (:token, :userId, :userAgent, :now, :now)
        on conflict (token) do update set user_id = excluded.user_id, user_agent = excluded.user_agent, last_seen_at = excluded.last_seen_at
        """,
    )
    suspend fun upsert(
        token: String,
        userId: UUID,
        userAgent: String?,
        now: Instant,
    ): Int

    @Modifying
    @Query("delete from push_tokens where token = :token and user_id = :userId")
    suspend fun deleteOwned(
        token: String,
        userId: UUID,
    ): Int

    @Modifying
    @Query("delete from push_tokens where token = :token")
    suspend fun deleteByToken(token: String): Int
}

/** An address that is called whenever an issue changes. */
@Table("webhooks")
class Webhook(
    @Id
    val id: UUID,
    var name: String,
    var enabled: Boolean,
    var url: String,
    var method: String,
    var contentType: String,
    /** A JSON object of header name to value. */
    var headers: String,
    /** Empty means the standard JSON payload. */
    var bodyTemplate: String,
    var signingSecret: String,
    /** Comma-separated IssueActivityType names; empty means every kind of change. */
    var eventTypes: String,
    /** Call the address once for each subscriber instead of once for the change. */
    var perRecipient: Boolean,
    var lastStatus: Int?,
    var lastError: String?,
    var lastAttemptAt: Instant?,
    val createdAt: Instant,
    var updatedAt: Instant,
) {
    val eventTypeSet: Set<String>
        get() = eventTypes.split(',').filter { it.isNotEmpty() }.toSet()
}

interface WebhookRepository : CoroutineCrudRepository<Webhook, UUID> {
    @Query("select * from webhooks order by created_at")
    fun findAllOrdered(): Flow<Webhook>

    @Query("select * from webhooks where enabled order by created_at")
    fun findEnabled(): Flow<Webhook>

    /** Touches only the result columns, so it cannot undo an edit an administrator is saving. */
    @Modifying
    @Query("update webhooks set last_status = :status, last_error = :error, last_attempt_at = :now where id = :id")
    suspend fun recordAttempt(
        id: UUID,
        status: Int?,
        error: String?,
        now: Instant,
    ): Int
}
