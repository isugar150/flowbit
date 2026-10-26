package com.flowbit.notification

import com.flowbit.common.web.PublicUrl
import com.flowbit.instance.application.InstanceService
import com.flowbit.issue.application.IssueQueries
import com.flowbit.issue.application.IssueView
import com.flowbit.issue.application.MyIssueScope
import com.flowbit.issue.application.Viewer
import com.flowbit.issue.domain.IssueStatus
import com.flowbit.user.application.UserService
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
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** What an administrator set for a member (D-216). A member nobody set anything for has it off. */
data class DailySummarySettings(
    val userId: UUID,
    val enabled: Boolean,
    /** In the member's time zone. */
    val sendTime: LocalTime,
    /** The member's own date it was last sent on. */
    val lastSentOn: LocalDate?,
)

/** `member_daily_summaries`. */
@Component
class DailySummaryStore(
    private val databaseClient: DatabaseClient,
) {
    suspend fun get(userId: UUID): DailySummarySettings =
        databaseClient
            .sql("select user_id, enabled, send_time, last_sent_on from member_daily_summaries where user_id = :userId")
            .bind("userId", userId)
            .map { row -> settingsOf(row) }
            .one()
            .awaitSingleOrNull() ?: DailySummarySettings(userId, enabled = false, sendTime = DEFAULT_TIME, lastSentOn = null)

    suspend fun save(
        userId: UUID,
        enabled: Boolean,
        sendTime: LocalTime,
    ) {
        databaseClient
            .sql(
                """
                insert into member_daily_summaries (user_id, enabled, send_time) values (:userId, :enabled, :sendTime)
                on conflict (user_id) do update set enabled = excluded.enabled, send_time = excluded.send_time
                """.trimIndent(),
            ).bind("userId", userId)
            .bind("enabled", enabled)
            .bind("sendTime", sendTime)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** The members it is switched on for, who can still sign in. */
    suspend fun allEnabled(): List<DailySummarySettings> =
        databaseClient
            .sql(
                """
                select s.user_id, s.enabled, s.send_time, s.last_sent_on
                from member_daily_summaries s join users u on u.id = s.user_id
                where s.enabled and u.status = 'ACTIVE'
                """.trimIndent(),
            ).map { row -> settingsOf(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun markSent(
        userId: UUID,
        date: LocalDate,
    ) {
        databaseClient
            .sql("update member_daily_summaries set last_sent_on = :date where user_id = :userId")
            .bind("date", date)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun settingsOf(row: io.r2dbc.spi.Readable) =
        DailySummarySettings(
            userId = checkNotNull(row.get("user_id", UUID::class.java)),
            enabled = checkNotNull(row.get("enabled", Boolean::class.javaObjectType)),
            sendTime = checkNotNull(row.get("send_time", LocalTime::class.java)),
            lastSentOn = row.get("last_sent_on", LocalDate::class.java),
        )

    companion object {
        val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
    }
}

/**
 * What goes into one member's daily summary (docs/features/inbox-and-daily-summary.md, D-216): their own tickets
 * (those assigned to them and not finished) by when they are due and by status, what was given to them since
 * yesterday, and how many notifications they have not read. Nothing to say gives no parts, and then nothing is sent.
 */
@Component
class DailySummaryBuilder(
    private val queries: IssueQueries,
    private val inbox: InboxStore,
    private val databaseClient: DatabaseClient,
) {
    /**
     * The parts of the summary, in the order they matter: each a heading line and what stands under it. Laid out to be
     * read as plain text (D-221): `■ 기한 지남 2건`, then a ticket to a line (`• FB-3 결제 오류 (10-24)`), the rest counted.
     */
    suspend fun sections(
        userId: UUID,
        today: LocalDate,
        since: Instant,
    ): List<List<String>> {
        val open =
            queries
                .listForUser(Viewer(userId, seesAllProjects = false), MyIssueScope.ASSIGNED, deleted = false)
                .filter { it.status != IssueStatus.DONE && it.status != IssueStatus.CANCELED }
        val byDue = open.filter { it.dueDate != null }.sortedBy { it.dueDate }
        val overdue = byDue.filter { it.dueDate!! < today }
        val dueToday = byDue.filter { it.dueDate == today }
        val dueSoon = byDue.filter { it.dueDate!! > today && it.dueDate!! <= today.plusDays(SOON_DAYS) }
        val assignedSince = assignedSince(userId, since)
        val newlyAssigned = open.filter { it.number in assignedSince }
        val unread = inbox.unreadCount(userId)

        return listOfNotNull(
            listed("기한 지남", overdue, today, withDue = true),
            listed("오늘 마감", dueToday, today, withDue = false),
            listed("${SOON_DAYS}일 안에 마감", dueSoon, today, withDue = true),
            listed("어제 이후 새로 할당된 티켓", newlyAssigned, today, withDue = false),
            statusPart(open),
            unreadPart(unread),
        )
    }

    /** `■ 기한 지남 2건`, the first few tickets one to a line, `… 외 1건`; null for none. */
    private fun listed(
        label: String,
        issues: List<IssueView>,
        today: LocalDate,
        withDue: Boolean,
    ): List<String>? {
        if (issues.isEmpty()) return null
        val tickets = issues.take(SHOWN).map { "• ${it.key} ${shorten(it.title)}" + if (withDue) due(it, today) else "" }
        val rest = issues.size - SHOWN
        return listOf("■ $label ${issues.size}건") + tickets + listOfNotNull("… 외 ${rest}건".takeIf { rest > 0 })
    }

    /** ` (10-24)`: the due date, with its year only when it is not this year. */
    private fun due(
        issue: IssueView,
        today: LocalDate,
    ): String =
        issue.dueDate
            ?.let { " (${if (it.year == today.year) it.toString().substring(MONTH_FROM) else it.toString()})" }
            .orEmpty()

    private fun shorten(title: String): String = if (title.length > TITLE_LENGTH) title.take(TITLE_LENGTH) + "…" else title

    /** `■ 내게 할당된 진행할 티켓 5건` and `백로그 1 · 준비됨 2 · 진행 중 2`; null when there are none. */
    private fun statusPart(open: List<IssueView>): List<String>? {
        if (open.isEmpty()) return null
        val counts =
            STATUS_NAMES.mapNotNull { (status, name) ->
                open.count { it.status == status }.takeIf { it > 0 }?.let { "$name $it" }
            }
        return listOf("■ 내게 할당된 진행할 티켓 ${open.size}건", counts.joinToString(" · "))
    }

    private fun unreadPart(unread: Int): List<String>? = if (unread > 0) listOf("■ 읽지 않은 알림 ${unread}건", "사이드바 아래 종에서 확인할 수 있습니다") else null

    /** The numbers of the issues the member was given since [since]. */
    private suspend fun assignedSince(
        userId: UUID,
        since: Instant,
    ): Set<Int> =
        databaseClient
            .sql(
                """
                select i.number from issue_assignees a join issues i on i.id = a.issue_id
                where a.user_id = :userId and a.created_at >= :since and i.deleted_at is null
                """.trimIndent(),
            ).bind("userId", userId)
            .bind("since", since)
            .map { row -> checkNotNull(row.get("number", Int::class.javaObjectType)) }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()

    private companion object {
        const val SOON_DAYS = 7L

        /** How many tickets a part names; the rest are counted. */
        const val SHOWN = 5
        const val TITLE_LENGTH = 40

        /** Where the month starts in `2026-10-24`. */
        const val MONTH_FROM = 5
        val STATUS_NAMES =
            listOf(IssueStatus.BACKLOG to "백로그", IssueStatus.READY to "준비됨", IssueStatus.IN_PROGRESS to "진행 중")
    }
}

/**
 * What sending a member's summary came to: whether there was anything to say, and how many of each way reached them
 * (mails handed to the server, browsers pushed to, API notifications that answered, and ones that did not).
 */
data class DailySummaryResult(
    val hadContent: Boolean,
    val mailed: Int = 0,
    val pushed: Int = 0,
    val apiReached: Int = 0,
    val apiFailed: Int = 0,
) {
    /** Whether it reached the member by any way. */
    val delivered: Boolean
        get() = mailed + pushed + apiReached > 0
}

/**
 * Sends the daily summary (D-216) to the member alone, by mail, web push and the API notifications that take it
 * (those that chose no kinds or chose "하루 요약"). It is not held or merged, and it is not kept in the inbox. Whoever it
 * is switched on for gets it once a day, at the time an administrator set in the member's own time zone, unless there
 * is nothing to say.
 */
@Service
class DailySummaryService(
    private val store: DailySummaryStore,
    private val builder: DailySummaryBuilder,
    private val mail: MailNotificationChannel,
    private val push: PushNotificationChannel,
    private val webhooks: WebhookNotificationChannel,
    private val userService: UserService,
    private val instanceService: InstanceService,
    private val publicUrl: PublicUrl,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Sends the member's summary now, and says which ways reached them. Nothing is sent when there is nothing to say. */
    suspend fun send(userId: UUID): DailySummaryResult {
        val user = userService.findActive(userId) ?: return DailySummaryResult(hadContent = false)
        val now = clock.instant()
        val today = now.atZone(zoneOf(user.timezone)).toLocalDate()
        val sections = builder.sections(userId, today, now.minus(LOOK_BACK))
        if (sections.isEmpty()) return DailySummaryResult(hadContent = false)
        val message =
            NotificationMessage(
                title = "[${instanceService.workspace().name}] 오늘의 요약",
                // A blank line between the parts, so that it reads as a list of short blocks in a chat window.
                lines = sections.flatMapIndexed { index, section -> if (index == 0) section else listOf("") + section },
                headline = "${user.displayName ?: user.loginId} 님, 오늘의 요약입니다",
                path = PATH,
                url = publicUrl.of(PATH),
                linkLabel = "내 티켓 열기",
                spaced = true,
            )
        val mailed = mail.mail(listOf(userId), message, "the daily summary of $userId")
        val pushed = push.pushTo(listOf(userId), message.title, (listOf(message.headline) + message.bodyLines).joinToString("\n"), "daily-summary", PATH)
        val api = webhooks.sendDailySummary(DailySummaryNotification(userId, message))
        return DailySummaryResult(hadContent = true, mailed = mailed, pushed = pushed, apiReached = api.reached, apiFailed = api.failed)
    }

    /**
     * One round: sends to each member whose time has come today and who has not had today's. A member whose
     * time passed hours ago (the server was off) still gets it, up to [CATCH_UP] late; a later one waits for tomorrow.
     */
    suspend fun sendDue() {
        val now = clock.instant()
        for (settings in store.allEnabled()) {
            val user = userService.findActive(settings.userId) ?: continue
            val zone = zoneOf(user.timezone)
            val date = now.atZone(zone).toLocalDate()
            val scheduled = date.atTime(settings.sendTime).atZone(zone).toInstant()
            if (settings.lastSentOn == date || now.isBefore(scheduled) || now.isAfter(scheduled.plus(CATCH_UP))) continue
            // Marked first: a summary goes out once a day, even when sending it fails.
            store.markSent(settings.userId, date)
            try {
                send(settings.userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not send the daily summary to {}", settings.userId, e)
            }
        }
    }

    /** The member's time zone, else the workspace's. */
    suspend fun zoneOf(timezone: String?): ZoneId =
        timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: runCatching { ZoneId.of(instanceService.defaults().timezone) }.getOrDefault(ZoneId.of("Asia/Seoul"))

    private companion object {
        const val PATH = "/my-issues"
        val LOOK_BACK: Duration = Duration.ofHours(24)
        val CATCH_UP: Duration = Duration.ofHours(6)
    }
}

/**
 * Runs [DailySummaryService.sendDue] in the background, every [interval], and forgets inbox notifications older
 * than [INBOX_KEPT] about once an hour. A failed round is logged and the next one tries again. Integration tests
 * switch it off and call the service themselves.
 */
@Component
class DailySummaryJob(
    private val service: DailySummaryService,
    private val inbox: InboxStore,
    private val clock: Clock,
    @Value("\${flowbit.daily-summary.enabled:true}") private val enabled: Boolean,
    @Value("\${flowbit.daily-summary.initial-delay:30s}") private val initialDelay: Duration,
    @Value("\${flowbit.daily-summary.interval:1m}") private val interval: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) return
        scope.launch {
            delay(initialDelay.toMillis())
            var forgottenAt = Instant.EPOCH
            while (isActive) {
                try {
                    service.sendDue()
                    val now = clock.instant()
                    if (Duration.between(forgottenAt, now) >= FORGET_EVERY) {
                        inbox.purgeBefore(now.minus(INBOX_KEPT))
                        forgottenAt = now
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("The daily summary round failed; trying again in {}", interval, e)
                }
                delay(interval.toMillis())
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    private companion object {
        val INBOX_KEPT: Duration = Duration.ofDays(90)
        val FORGET_EVERY: Duration = Duration.ofHours(1)
    }
}
