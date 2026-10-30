package com.flowbit.mcp

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ValidationException
import com.flowbit.issue.application.IssueLookup
import com.flowbit.project.application.ProjectAccessSql
import com.flowbit.project.application.ProjectService
import com.flowbit.user.application.UserService
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/** Bounded evidence queries. Stored timestamps describe records, never hours worked. */
@Service
class McpWorkLogs(
    private val database: DatabaseClient,
    private val users: UserService,
    private val projects: ProjectService,
    private val issues: IssueLookup,
    private val markdown: McpMarkdown,
    private val json: JsonMapper,
) {
    suspend fun query(
        user: AuthenticatedUser,
        args: Map<String, Any?>,
        commentsOnly: Boolean,
    ): Map<String, Any?> {
        fun invalid(
            field: String,
            message: String,
        ): Nothing = throw ValidationException(field, "INVALID", message)

        fun uuid(field: String): UUID? = (args[field] as? String)?.let { runCatching { UUID.fromString(it) }.getOrElse { invalid(field, "올바른 UUID를 지정하세요") } }

        fun date(field: String): LocalDate =
            runCatching {
                val value = args[field] as String
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
                LocalDate.parse(value).also { require(it.year >= 1) }
            }.getOrElse { invalid(field, "YYYY-MM-DD 날짜를 지정하세요") }
        val from = date("from")
        val to = date("to")
        if (to < from || ChronoUnit.DAYS.between(from, to) >= 366) invalid("to", "시작일부터 최대 366일을 지정하세요")
        val zone =
            runCatching { ZoneId.of(args["timezone"] as? String ?: users.profile(user.id).timezone) }
                .getOrElse { invalid("timezone", "올바른 시간대를 지정하세요") }
        val start = runCatching { from.atStartOfDay(zone).toInstant() }.getOrElse { invalid("from", "날짜 범위를 확인하세요") }
        val end = runCatching { to.plusDays(1).atStartOfDay(zone).toInstant() }.getOrElse { invalid("to", "날짜 범위를 확인하세요") }
        val author =
            when (args["userId"]) {
                null, "me" -> user.id
                "all" -> null
                else -> uuid("userId")
            }
        val project = uuid("projectId")?.also { projects.access(user, it.toString()) }
        val issue = (args["issue"] as? String)?.let { issues.access(user, issueNumber(it)).issue.id }
        val limit = (args["limit"] as? Number)?.toLong() ?: 50L
        if (limit !in 1..100) invalid("limit", "1~100으로 지정하세요")
        val dateField = args["dateField"] as? String ?: "createdAt"
        val bindings = linkedMapOf<String, Any>("viewer" to user.id, "start" to start, "end" to end, "limit" to limit + 1)
        val filters = mutableListOf("e.occurred_at >= :start", "e.occurred_at < :end")

        fun filter(
            sql: String,
            key: String,
            value: Any?,
        ) {
            if (value != null) {
                filters += sql
                bindings[key] = value
            }
        }
        filter("e.actor_id = :author", "author", author)
        filter("t.project_id = :project", "project", project)
        filter("t.issue_id = :issue", "issue", issue)
        if (commentsOnly) {
            filter("e.id = :comment", "comment", uuid("commentId"))
            filter("e.parent_id = :parent", "parent", uuid("parentId"))
            filter("e.thread_id = :thread", "thread", uuid("threadId"))
            val q = (args["q"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (q != null && q.length > 500) invalid("q", "검색어는 500자 이내로 지정하세요")
            filter("strpos(lower(e.body), lower(:q)) > 0", "q", q)
        }
        val fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest(json.writeValueAsBytes(listOf(commentsOnly, dateField, zone.id, bindings.filterKeys { it != "limit" })))
                .joinToString("") { "%02x".format(it) }
        (args["cursor"] as? String)?.let { encoded ->
            val parts =
                runCatching {
                    require(encoded.length <= 512)
                    String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8).split('|').also {
                        require(it.size == 4 && it[0] == fingerprint)
                        bindings["cursorTime"] = Instant.parse(it[1])
                        bindings["cursorKind"] = it[2]
                        bindings["cursorId"] = UUID.fromString(it[3])
                    }
                }.getOrElse { invalid("cursor", "같은 조회 조건에서 반환된 커서를 사용하세요") }
            if (parts[2] !in KINDS) invalid("cursor", "올바른 커서를 사용하세요")
            filters += "(e.occurred_at, e.kind, e.id) > (:cursorTime, :cursorKind, :cursorId)"
        }
        val commentEvents =
            if (commentsOnly) {
                "select c.*, c.${if (dateField == "updatedAt") "updated_at" else "created_at"} as occurred_at, 'COMMENT'::text as kind, 'COMMENT'::text as event_type from comments c"
            } else {
                """
                select c.*, c.created_at as occurred_at, 'COMMENT_ADDED'::text as kind, 'COMMENT_ADDED'::text as event_type from comments c
                union all
                select c.*, c.updated_at as occurred_at, 'COMMENT_UPDATED'::text as kind, 'COMMENT_UPDATED'::text as event_type from comments c
                where c.updated_at > c.created_at
                union all
                select a.id, 'ISSUE', a.issue_id, a.actor_id, null, null, null, null, null, null, a.created_at, a.created_at,
                       a.detail::text, a.created_at, 'ISSUE_ACTIVITY', a.type
                from issue_activities a
                union all
                select a.id, a.target_type, a.target_id, a.actor_id, null, null, null, null, null, null, a.created_at, a.created_at,
                       a.detail::text, a.created_at, 'CONTENT_ACTIVITY', a.type
                from content_activities a
                """.trimIndent()
            }
        var spec =
            database.sql(
                """
                with targets as ($TARGETS), comments as not materialized ($COMMENTS), events as ($commentEvents)
                select e.*, t.title, t.path, t.project_id, t.issue_id, t.issue_number, t.issue_key,
                       u.login_id, u.display_name
                from events e join targets t on t.target_type = e.target_type and t.id = e.target_id
                left join users u on u.id = e.actor_id
                where ${filters.joinToString(" and ")}
                order by e.occurred_at, e.kind, e.id
                limit :limit
                """.trimIndent(),
            )
        bindings.forEach { (key, value) -> spec = spec.bind(key, value) }
        val rows =
            spec
                .map { row ->
                    fun text(key: String) = row.get(key, String::class.java)

                    fun id(key: String) = row.get(key, UUID::class.java)

                    fun instant(key: String) = checkNotNull(row.get(key, OffsetDateTime::class.java)).toInstant()
                    val content = text("content")
                    mapOf(
                        "id" to id("id"),
                        "kind" to text("kind"),
                        "type" to text("event_type"),
                        "occurredAt" to instant("occurred_at"),
                        "actor" to id("actor_id")?.let { mapOf("id" to it, "loginId" to text("login_id"), "displayName" to text("display_name")) },
                        "target" to
                            mapOf(
                                "type" to text("target_type"),
                                "id" to id("target_id"),
                                "title" to text("title"),
                                "path" to text("path"),
                                "projectId" to id("project_id"),
                                "issueId" to id("issue_id"),
                                "issueKey" to text("issue_key"),
                            ),
                        "comment" to
                            content?.let {
                                mapOf(
                                    "id" to id("id"),
                                    "parentId" to id("parent_id"),
                                    "threadId" to id("thread_id"),
                                    "blockId" to text("block_id"),
                                    "quote" to text("quote"),
                                    "content" to json.readValue(it, Any::class.java),
                                    "body" to text("body"),
                                    "markdown" to markdown.render(it),
                                    "createdAt" to instant("created_at"),
                                    "updatedAt" to instant("updated_at"),
                                )
                            },
                        "detail" to text("detail")?.let { json.readValue(it, Any::class.java) },
                    )
                }.all()
                .collectList()
                .awaitSingle()
        val items = rows.take(limit.toInt())
        val next =
            if (rows.size > limit) {
                items.last().let {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "$fingerprint|${it["occurredAt"]}|${it["kind"]}|${it["id"]}".toByteArray(Charsets.UTF_8),
                    )
                }
            } else {
                null
            }
        return mapOf(
            "from" to from,
            "to" to to,
            "timezone" to zone.id,
            "fromInstant" to start,
            "toExclusive" to end,
            "userId" to (author?.toString() ?: "all"),
            "projectId" to project,
            "issueId" to issue,
            "dateField" to if (commentsOnly) dateField else "occurredAt",
            "items" to items,
            "nextCursor" to next,
            "limitations" to
                listOf(
                    "기록 시각은 실제 작업 시작·종료·소요 시간이 아닙니다.",
                    "댓글은 현재 본문과 작성·마지막 수정 시각만 제공합니다. 과거 원문과 중간 수정은 복원하지 않습니다.",
                    "현재 접근 가능한 대상만 포함하며 삭제된 댓글·티켓·문서는 제외합니다. 페이지 조회 중 기록이 바뀔 수 있습니다.",
                    "nextCursor가 있으면 끝까지 조회한 뒤 댓글 내용과 활동 근거를 분석하세요.",
                ),
        )
    }

    private companion object {
        val KINDS = setOf("COMMENT", "COMMENT_ADDED", "COMMENT_UPDATED", "ISSUE_ACTIVITY", "CONTENT_ACTIVITY")
        val TARGETS =
            """
            select 'ISSUE'::text as target_type, i.id, i.title, '/issue/' || s.workspace_key || '-' || i.number as path,
                   i.project_id, i.id as issue_id, i.number as issue_number, s.workspace_key || '-' || i.number as issue_key
            from issues i cross join instance_settings s
            where i.deleted_at is null and (i.project_id is null or ${ProjectAccessSql.mayEnter("i.project_id", ":viewer")})
            union all
            select 'PROJECT', p.id, p.name, '/project/' || p.id, p.id, null, null, null
            from projects p where ${ProjectAccessSql.mayEnter("p.id", ":viewer")}
            union all
            select 'DOCUMENT', d.id, d.title, '/doc/' || d.id, coalesce(d.project_id, i.project_id), i.id, i.number,
                   case when i.id is not null then s.workspace_key || '-' || i.number end
            from documents d left join issues i on i.id = d.issue_id cross join instance_settings s
            where d.deleted_at is null and (d.issue_id is null or (i.id is not null and i.deleted_at is null))
              and ((d.issue_id is not null and i.project_id is null)
                   or ${ProjectAccessSql.mayEnter("coalesce(d.project_id, i.project_id)", ":viewer")})
            """.trimIndent()
        val COMMENTS =
            """
            select c.id, 'ISSUE'::text as target_type, c.issue_id as target_id, c.author_id as actor_id,
                   c.parent_id, null::uuid as thread_id, null::text as block_id, null::text as quote,
                   c.content::text as content, c.body, c.created_at, c.updated_at, null::text as detail
            from issue_comments c where c.deleted_at is null
            union all
            select c.id, c.target_type, c.target_id, c.author_id, c.parent_id, null, null, null,
                   c.content::text, c.body, c.created_at, c.updated_at, null
            from content_comments c where c.deleted_at is null
            union all
            select c.id, t.target_type, t.target_id, c.author_id, null, c.thread_id, t.block_id, t.quote,
                   c.content::text, c.body, c.created_at, c.updated_at, null
            from content_thread_comments c join content_threads t on t.id = c.thread_id where c.deleted_at is null
            """.trimIndent()
    }
}
