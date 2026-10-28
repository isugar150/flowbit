package com.flowbit.automation

import com.flowbit.common.UuidV7
import com.flowbit.common.error.NotFoundException
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
class AutomationStore(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    suspend fun rules(projectId: UUID): List<AutomationRule> =
        db
            .sql("select * from project_automations where project_id = :project order by created_at, id")
            .bind("project", projectId)
            .map { row, _ -> rule(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun save(
        projectId: UUID,
        id: UUID?,
        input: AutomationInput,
    ): AutomationRule {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val ruleId = id ?: UuidV7.generate(clock.instant())
        val sql =
            if (id == null) {
                "insert into project_automations (id, project_id, name, enabled, from_status_id, to_status_id, actions, created_at, updated_at) values (:id, :project, :name, :enabled, :from, :to, cast(:actions as jsonb), :now, :now)"
            } else {
                "update project_automations set name = :name, enabled = :enabled, from_status_id = :from, to_status_id = :to, actions = cast(:actions as jsonb), updated_at = :now where id = :id and project_id = :project"
            }
        var spec =
            db
                .sql(sql)
                .bind("id", ruleId)
                .bind("project", projectId)
                .bind("name", input.name)
                .bind("enabled", input.enabled)
                .bind("to", input.toStatusId)
                .bind("actions", json.writeValueAsString(input.actions))
                .bind("now", now)
        spec = input.fromStatusId?.let { spec.bind("from", it) } ?: spec.bindNull("from", UUID::class.java)
        if (spec.fetch().rowsUpdated().awaitSingle() == 0L) throw notFound()
        return rules(projectId).first { it.id == ruleId }
    }

    suspend fun delete(
        projectId: UUID,
        id: UUID,
    ) {
        if (db
                .sql("delete from project_automations where id = :id and project_id = :project")
                .bind("id", id)
                .bind("project", projectId)
                .fetch()
                .rowsUpdated()
                .awaitSingle() == 0L
        ) {
            throw notFound()
        }
    }

    /** Claim before applying actions; the unique key also protects callers that announce a change twice. */
    suspend fun claim(
        rule: AutomationRule,
        issueId: UUID,
        version: Int,
    ): UUID? {
        val id = UuidV7.generate(clock.instant())
        val inserted =
            db
                .sql(
                    "insert into automation_runs (id, rule_id, project_id, issue_id, issue_version, rule_name, results, created_at) values (:id, :rule, :project, :issue, :version, :name, '[]', :now) on conflict (rule_id, issue_id, issue_version) do nothing",
                ).bind("id", id)
                .bind("rule", rule.id)
                .bind("project", rule.projectId)
                .bind("issue", issueId)
                .bind("version", version)
                .bind("name", rule.name)
                .bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        return id.takeIf { inserted > 0 }
    }

    suspend fun finish(
        id: UUID,
        results: List<AutomationResult>,
    ) {
        db
            .sql("update automation_runs set results = cast(:results as jsonb) where id = :id")
            .bind("results", json.writeValueAsString(results))
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun runs(projectId: UUID): List<AutomationRun> =
        db
            .sql(
                "select r.*, i.number, i.title, (select workspace_key from instance_settings where id = 1) || '-' || i.number as issue_key from automation_runs r join issues i on i.id = r.issue_id where r.project_id = :project order by r.created_at desc, r.id desc limit 50",
            ).bind("project", projectId)
            .map { row, _ ->
                AutomationRun(
                    checkNotNull(row.get("id", UUID::class.java)),
                    checkNotNull(row.get("rule_name", String::class.java)),
                    checkNotNull(row.get("number", Int::class.javaObjectType)),
                    checkNotNull(row.get("issue_key", String::class.java)),
                    checkNotNull(row.get("title", String::class.java)),
                    json.readValue(checkNotNull(row.get("results", String::class.java)), Array<AutomationResult>::class.java).toList(),
                    checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

    private fun rule(row: Readable) =
        AutomationRule(
            checkNotNull(row.get("id", UUID::class.java)),
            checkNotNull(row.get("project_id", UUID::class.java)),
            checkNotNull(row.get("name", String::class.java)),
            checkNotNull(row.get("enabled", Boolean::class.javaObjectType)),
            row.get("from_status_id", UUID::class.java),
            checkNotNull(row.get("to_status_id", UUID::class.java)),
            json.readValue(checkNotNull(row.get("actions", String::class.java)), Array<AutomationAction>::class.java).toList(),
            checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
        )

    private fun notFound() = NotFoundException("AUTOMATION_NOT_FOUND", "자동화 규칙을 찾을 수 없습니다")
}
