package com.flowbit.auditlog

import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.error.ValidationException
import com.flowbit.instance.application.InstanceService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

data class AuditActor(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
)

data class AuditLogEntry(
    val id: UUID,
    val occurredAt: Instant,
    val type: String,
    val actor: AuditActor?,
    val ipAddress: String?,
    val userAgent: String?,
    val detail: Map<String, String>,
)

/** One page of the log, newest first, with every kind the log can hold (for the filter). */
data class AuditLogPage(
    val total: Long,
    val types: List<String>,
    val items: List<AuditLogEntry>,
)

/**
 * The administrator's view of `audit_events` (docs/features/account-security.md): newest first,
 * narrowed by kind, by who did it, and by day (in the workspace's time zone), a page at a time.
 */
@Service
class AuditLogService(
    private val databaseClient: DatabaseClient,
    private val instanceService: InstanceService,
    private val jsonMapper: JsonMapper,
) {
    suspend fun page(
        type: String?,
        actorQuery: String?,
        fromDate: String?,
        toDate: String?,
        limit: Int,
        offset: Int,
    ): AuditLogPage {
        val kind = type?.takeIf { it.isNotBlank() }
        if (kind != null && AuditEventType.entries.none { it.name == kind }) {
            throw ValidationException("type", "UNKNOWN_TYPE", "알 수 없는 기록 종류입니다")
        }
        val zone = zoneOf(instanceService.defaults().timezone)
        val from = fromDate?.takeIf { it.isNotBlank() }?.let { day("from", it).atStartOfDay(zone).toInstant() }
        // The last day is included, so the range ends where the next day begins.
        val to = toDate?.takeIf { it.isNotBlank() }?.let { day("to", it).plusDays(1).atStartOfDay(zone).toInstant() }

        val conditions = mutableListOf<String>()
        val binds = linkedMapOf<String, Any>()
        if (kind != null) {
            conditions += "a.event_type = :type"
            binds["type"] = kind
        }
        actorQuery?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions += "(lower(u.login_id) like :actor escape '\\' or lower(coalesce(u.display_name, '')) like :actor escape '\\')"
            binds["actor"] = "%" +
                it
                    .lowercase()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%"
        }
        if (from != null) {
            conditions += "a.occurred_at >= :from"
            binds["from"] = OffsetDateTime.ofInstant(from, ZoneOffset.UTC)
        }
        if (to != null) {
            conditions += "a.occurred_at < :to"
            binds["to"] = OffsetDateTime.ofInstant(to, ZoneOffset.UTC)
        }
        val where = if (conditions.isEmpty()) "" else "where " + conditions.joinToString(" and ")
        val join = "from audit_events a left join users u on u.id = a.actor_user_id"

        val total =
            binds.entries
                .fold(databaseClient.sql("select count(*) $join $where")) { spec, (name, value) -> spec.bind(name, value) }
                .map { row, _ -> (row.get(0) as Number).toLong() }
                .one()
                .awaitSingle()
        val items =
            binds.entries
                .fold(
                    databaseClient.sql(
                        """
                        select a.id, a.occurred_at, a.event_type, host(a.ip_address) as ip, a.user_agent, a.detail::text as detail,
                               u.id as actor_id, u.login_id, u.display_name
                        $join $where
                        order by a.occurred_at desc, a.id desc
                        limit :limit offset :offset
                        """.trimIndent(),
                    ),
                ) { spec, (name, value) -> spec.bind(name, value) }
                .bind("limit", limit.coerceIn(1, MAX_LIMIT))
                .bind("offset", offset.coerceAtLeast(0))
                .map { row, _ ->
                    val actorId = row.get("actor_id", UUID::class.java)
                    AuditLogEntry(
                        id = checkNotNull(row.get("id", UUID::class.java)),
                        occurredAt = checkNotNull(row.get("occurred_at", OffsetDateTime::class.java)).toInstant(),
                        type = checkNotNull(row.get("event_type", String::class.java)),
                        actor =
                            actorId?.let {
                                AuditActor(it, checkNotNull(row.get("login_id", String::class.java)), row.get("display_name", String::class.java))
                            },
                        ipAddress = row.get("ip", String::class.java),
                        userAgent = row.get("user_agent", String::class.java),
                        detail = detailOf(row.get("detail", String::class.java)),
                    )
                }.all()
                .collectList()
                .awaitSingle()
        return AuditLogPage(total, AuditEventType.entries.map { it.name }, items)
    }

    private fun detailOf(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val parsed = jsonMapper.readValue(json, Map::class.java)
        return parsed.entries.associate { (key, value) -> key.toString() to value.toString() }
    }

    private fun day(
        field: String,
        value: String,
    ): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeException) {
            throw ValidationException(field, "INVALID_DATE", "날짜는 2026-10-28 처럼 적어야 합니다")
        }

    private fun zoneOf(name: String): ZoneId =
        try {
            ZoneId.of(name)
        } catch (_: DateTimeException) {
            ZoneId.of("Asia/Seoul")
        }

    companion object {
        const val MAX_LIMIT = 100
    }
}

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/audit")
class AuditLogController(
    private val service: AuditLogService,
) {
    @GetMapping
    suspend fun list(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): AuditLogPage = service.page(type, actor, from, to, limit, offset)
}
