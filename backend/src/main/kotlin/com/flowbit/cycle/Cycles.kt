package com.flowbit.cycle

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.ValidationException
import com.flowbit.instance.application.InstanceService
import com.flowbit.project.application.AdminProjectAudit
import com.flowbit.project.application.ProjectService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class CycleSettings(
    val enabled: Boolean,
    /** How many weeks one cycle lasts: 1 to 4. */
    val weeks: Int,
    /** The weekday a cycle starts on, ISO: 1 = Monday … 7 = Sunday. */
    val startDay: Int,
)

data class CycleView(
    val id: UUID,
    val number: Int,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    /** The issues in the cycle in use, canceled ones not counted, and how many of them are done. */
    val total: Int,
    val done: Int,
)

data class ProjectCycles(
    val settings: CycleSettings,
    /** Today in the workspace's time zone: what "current" is measured against. */
    val today: LocalDate,
    val current: CycleView?,
    val upcoming: CycleView?,
    /** The latest finished ones, newest first. */
    val past: List<CycleView>,
)

/**
 * Cycles (docs/features/cycles.md): a project that turns them on gets a repeating work period of a few weeks that makes itself.
 * Whatever is not finished when one ends moves on to the next. The cycle is a plain label on an issue (`issues.cycle_id`); nothing
 * about the issue changes because it is in one.
 */
@Service
class CycleService(
    private val databaseClient: DatabaseClient,
    private val projectService: ProjectService,
    private val instanceService: InstanceService,
    private val clock: Clock,
) {
    /** The cycles of a project as its members see them; they are made first if the day has moved on. */
    suspend fun overview(
        user: AuthenticatedUser,
        projectId: String,
        administrator: Boolean = false,
    ): ProjectCycles {
        val access = projectService.access(user, projectId, administrator)
        ensure(access.project.id)
        return read(access.project.id)
    }

    /** [administrator]: from the administrator screen, for any project in use (D-236). */
    suspend fun updateSettings(
        user: AuthenticatedUser,
        projectId: String,
        settings: CycleSettings,
        administrator: Boolean = false,
    ): ProjectCycles {
        val access = projectService.access(user, projectId, administrator)
        if (!access.role.canManage) throw ForbiddenException("사이클 설정은 프로젝트 관리 역할만 바꿀 수 있습니다")
        if (settings.weeks !in 1..4) throw ValidationException("weeks", "INVALID", "사이클은 1~4주여야 합니다")
        if (settings.startDay !in 1..7) throw ValidationException("startDay", "INVALID", "시작 요일이 올바르지 않습니다")
        databaseClient
            .sql("update projects set cycles_enabled = :enabled, cycle_weeks = :weeks, cycle_start_day = :startDay, updated_at = :now where id = :id")
            .bind("enabled", settings.enabled)
            .bind("weeks", settings.weeks.toShort())
            .bind("startDay", settings.startDay.toShort())
            .bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .bind("id", access.project.id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        ensure(access.project.id)
        return read(access.project.id)
    }

    /** Whether the cycle belongs to the project: for putting an issue in it. */
    suspend fun belongsTo(
        projectId: UUID?,
        cycleId: UUID,
    ): Boolean =
        projectId != null &&
            databaseClient
                .sql("select count(*) from cycles where id = :id and project_id = :project")
                .bind("id", cycleId)
                .bind("project", projectId)
                .map { row, _ -> (row.get(0) as Number).toInt() }
                .one()
                .awaitSingle() > 0

    /** Every project that uses cycles gets the ones it is missing and moves unfinished issues on; for the hourly job. */
    suspend fun ensureAll() {
        val projects =
            databaseClient
                .sql("select id from projects where cycles_enabled and archived_at is null")
                .map { row, _ -> checkNotNull(row.get("id", UUID::class.java)) }
                .all()
                .collectList()
                .awaitSingle()
        projects.forEach { ensure(it) }
    }

    /**
     * Makes the cycle of today and the one after it if they are missing, and moves what is not finished in a cycle that has ended into
     * the current one. Safe to repeat: with nothing missing it changes nothing.
     */
    suspend fun ensure(projectId: UUID) {
        val settings = settingsOf(projectId)
        if (!settings.enabled) return
        val today = today()
        val length = settings.weeks * 7L
        val last =
            databaseClient
                .sql("select number, ends_on from cycles where project_id = :id order by number desc limit 1")
                .bind("id", projectId)
                .map { row, _ -> checkNotNull(row.get("number", Int::class.javaObjectType)) to checkNotNull(row.get("ends_on", LocalDate::class.java)) }
                .one()
                .awaitSingleOrNull()
        var number = (last?.first ?: 0) + 1
        var start = last?.second?.plusDays(1) ?: today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(settings.startDay)))
        while (!start.isAfter(today.plusDays(length))) {
            databaseClient
                .sql("insert into cycles (id, project_id, number, starts_on, ends_on, created_at) values (:id, :project, :number, :starts, :ends, :now)")
                .bind("id", UuidV7.generate(clock.instant()))
                .bind("project", projectId)
                .bind("number", number)
                .bind("starts", start)
                .bind("ends", start.plusDays(length - 1))
                .bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            number += 1
            start = start.plusDays(length)
        }
        val current =
            databaseClient
                .sql("select id from cycles where project_id = :id and starts_on <= :today and ends_on >= :today")
                .bind("id", projectId)
                .bind("today", today)
                .map { row, _ -> checkNotNull(row.get("id", UUID::class.java)) }
                .one()
                .awaitSingleOrNull() ?: return
        // What was not finished when its cycle ended carries on in this one.
        databaseClient
            .sql(
                """
                update issues set cycle_id = :current
                where cycle_id in (select id from cycles where project_id = :project and ends_on < :today)
                  and status not in ('DONE', 'CANCELED') and deleted_at is null
                """.trimIndent(),
            ).bind("current", current)
            .bind("project", projectId)
            .bind("today", today)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun read(projectId: UUID): ProjectCycles {
        val today = today()
        val cycles =
            databaseClient
                .sql(
                    """
                    select c.id, c.number, c.starts_on, c.ends_on,
                           (select count(*) from issues i where i.cycle_id = c.id and i.deleted_at is null and i.status <> 'CANCELED') as total,
                           (select count(*) from issues i where i.cycle_id = c.id and i.deleted_at is null and i.status = 'DONE') as done
                    from cycles c where c.project_id = :id order by c.number desc limit 12
                    """.trimIndent(),
                ).bind("id", projectId)
                .map { row, _ ->
                    CycleView(
                        id = checkNotNull(row.get("id", UUID::class.java)),
                        number = checkNotNull(row.get("number", Int::class.javaObjectType)),
                        startsOn = checkNotNull(row.get("starts_on", LocalDate::class.java)),
                        endsOn = checkNotNull(row.get("ends_on", LocalDate::class.java)),
                        total = checkNotNull(row.get("total", Long::class.javaObjectType)).toInt(),
                        done = checkNotNull(row.get("done", Long::class.javaObjectType)).toInt(),
                    )
                }.all()
                .collectList()
                .awaitSingle()
        return ProjectCycles(
            settings = settingsOf(projectId),
            today = today,
            current = cycles.firstOrNull { !it.startsOn.isAfter(today) && !it.endsOn.isBefore(today) },
            upcoming = cycles.filter { it.startsOn.isAfter(today) }.minByOrNull { it.startsOn },
            past = cycles.filter { it.endsOn.isBefore(today) }.take(PAST_SHOWN),
        )
    }

    private suspend fun settingsOf(projectId: UUID): CycleSettings =
        databaseClient
            .sql("select cycles_enabled, cycle_weeks, cycle_start_day from projects where id = :id")
            .bind("id", projectId)
            .map { row, _ ->
                CycleSettings(
                    enabled = checkNotNull(row.get("cycles_enabled", Boolean::class.javaObjectType)),
                    weeks = checkNotNull(row.get("cycle_weeks", Short::class.javaObjectType)).toInt(),
                    startDay = checkNotNull(row.get("cycle_start_day", Short::class.javaObjectType)).toInt(),
                )
            }.one()
            .awaitSingle()

    private suspend fun today(): LocalDate {
        val zone =
            try {
                ZoneId.of(instanceService.defaults().timezone)
            } catch (_: DateTimeException) {
                ZoneId.of("Asia/Seoul")
            }
        return LocalDate.now(clock.withZone(zone))
    }

    private companion object {
        const val PAST_SHOWN = 5
    }
}

/** Makes the cycles that are missing and moves unfinished issues on, once an hour (a restart also does it at once). */
@Component
class CycleJob(
    private val service: CycleService,
    @Value("\${flowbit.cycles.enabled:true}") private val enabled: Boolean,
    @Value("\${flowbit.cycles.interval:1h}") private val interval: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) return
        scope.launch {
            while (isActive) {
                try {
                    service.ensureAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Making the cycles failed; trying again in {}", interval, e)
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

@RestController
@RequestMapping("/api/v1/projects/{projectId}/cycles")
class CycleController(
    private val service: CycleService,
) {
    @GetMapping
    suspend fun overview(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectCycles = service.overview(user, projectId)

    @PutMapping("/settings")
    suspend fun settings(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: CycleSettings,
    ): ProjectCycles = service.updateSettings(user, projectId, body)
}

/**
 * The cycle settings of any project in use, for the administrator screen (D-236); the same answers as [CycleController].
 * `/api/v1/admin` requires the ADMIN role (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin/projects/{projectId}/cycles")
class AdminCycleController(
    private val service: CycleService,
    private val audit: AdminProjectAudit,
) {
    @GetMapping
    suspend fun overview(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
    ): ProjectCycles = service.overview(admin, projectId, administrator = true)

    @PutMapping("/settings")
    suspend fun settings(
        @AuthenticationPrincipal admin: AuthenticatedUser,
        @PathVariable projectId: String,
        @RequestBody body: CycleSettings,
        exchange: ServerWebExchange,
    ): ProjectCycles {
        val cycles = service.updateSettings(admin, projectId, body, administrator = true)
        audit.record(admin, exchange, projectId, "cycles", mapOf("enabled" to body.enabled.toString()))
        return cycles
    }
}
