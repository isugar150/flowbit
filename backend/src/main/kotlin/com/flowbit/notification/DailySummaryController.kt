package com.flowbit.notification

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.user.domain.UserRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/** A member's daily summary as administrators see it. `time` is `09:00`, in the member's own time zone. */
data class DailySummaryResponse(
    val enabled: Boolean,
    val time: String,
    val timezone: String,
    val lastSentOn: LocalDate?,
)

data class UpdateDailySummaryRequest(
    val enabled: Boolean,
    val time: String,
)

/** Whether the summary was sent; false when the member had nothing in it (D-216). */
data class SendDailySummaryResponse(
    val sent: Boolean,
)

/**
 * What an administrator sets for a member under 관리자 설정 > 구성원 (docs/features/inbox-and-daily-summary.md, D-216):
 * whether they get the daily summary, and at what time. Off until an administrator switches it on. Everything under
 * `/api/v1/admin` requires the ADMIN role (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin/members/{id}/daily-summary")
class AdminDailySummaryController(
    private val store: DailySummaryStore,
    private val service: DailySummaryService,
    private val userRepository: UserRepository,
    private val auditService: AuditService,
) {
    @GetMapping
    suspend fun get(
        @PathVariable id: UUID,
    ): DailySummaryResponse = response(id)

    @PutMapping
    suspend fun update(
        @AuthenticationPrincipal actor: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: UpdateDailySummaryRequest,
        exchange: ServerWebExchange,
    ): DailySummaryResponse {
        requireMember(id)
        val time =
            try {
                LocalTime.parse(body.time.trim(), TIME)
            } catch (_: DateTimeParseException) {
                throw ValidationException("time", "INVALID", "시각은 09:00 모양으로 적어야 합니다")
            }
        store.save(id, body.enabled, time)
        auditService.record(
            AuditEventType.MEMBER_DAILY_SUMMARY_CHANGED,
            actor.id,
            ClientInfo.from(exchange),
            mapOf("memberId" to id.toString(), "enabled" to body.enabled.toString(), "time" to TIME.format(time)),
        )
        return response(id)
    }

    /** Sends the member's summary now, as a try; the day's own is not used up. */
    @PostMapping("/send")
    suspend fun send(
        @PathVariable id: UUID,
    ): SendDailySummaryResponse {
        requireMember(id)
        return SendDailySummaryResponse(service.send(id))
    }

    private suspend fun response(id: UUID): DailySummaryResponse {
        val user = requireMember(id)
        val settings = store.get(id)
        return DailySummaryResponse(settings.enabled, TIME.format(settings.sendTime), service.zoneOf(user.timezone).id, settings.lastSentOn)
    }

    private suspend fun requireMember(id: UUID) = userRepository.findById(id) ?: throw NotFoundException("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다")

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
