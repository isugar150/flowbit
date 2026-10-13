package com.flowbit.common.audit

import com.flowbit.common.UuidV7
import com.flowbit.common.web.ClientInfo
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class AuditEventType {
    SETUP_COMPLETED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    LOGOUT,
    MEMBER_CREATED,
    MEMBER_ROLE_CHANGED,
    MEMBER_DISABLED,
    MEMBER_ENABLED,
    MEMBER_PASSWORD_RESET,
    MEMBER_PROVISIONED,
    MEMBER_CONVERTED,
    AUTH_SETTINGS_CHANGED,
    PROJECT_CREATED,
    BACKUP_EXPORTED,
    BACKUP_RESTORED,
}

/** Append-only record of security-relevant events. Never pass passwords or other secrets in `detail`. */
@Service
class AuditService(
    private val databaseClient: DatabaseClient,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    suspend fun record(
        type: AuditEventType,
        actorUserId: UUID?,
        client: ClientInfo,
        detail: Map<String, String> = emptyMap(),
    ) {
        val now = clock.instant()
        var spec =
            databaseClient
                .sql(
                    """
                    insert into audit_events (id, occurred_at, actor_user_id, event_type, ip_address, user_agent, detail)
                    values (:id, :occurredAt, :actorUserId, :eventType, cast(:ipAddress as inet), :userAgent, cast(:detail as jsonb))
                    """.trimIndent(),
                ).bind("id", UuidV7.generate(now))
                .bind("occurredAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .bind("eventType", type.name)
                .bind("detail", jsonMapper.writeValueAsString(detail))
        spec = if (actorUserId != null) spec.bind("actorUserId", actorUserId) else spec.bindNull("actorUserId", UUID::class.java)
        spec = if (client.ipAddress != null) spec.bind("ipAddress", client.ipAddress) else spec.bindNull("ipAddress", String::class.java)
        spec = if (client.userAgent != null) spec.bind("userAgent", client.userAgent) else spec.bindNull("userAgent", String::class.java)
        spec.fetch().rowsUpdated().awaitSingle()
    }
}
