package com.flowbit.user.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.web.ClientInfo
import com.flowbit.media.application.ImageStorage
import com.flowbit.user.application.MemberAdminService
import com.flowbit.user.application.UserService
import com.flowbit.user.domain.AuthSource
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRole
import com.flowbit.user.domain.UserStatus
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.util.UUID

/** A person as other signed-in users see them. */
data class UserSummaryResponse(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
) {
    companion object {
        fun from(user: User) = UserSummaryResponse(user.id, user.loginId, user.displayName, ImageStorage.urlOf(user.avatarKey))
    }
}

/** A member as administrators see them. */
data class MemberResponse(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val role: UserRole,
    val status: UserStatus,
    val authSource: AuthSource,
    val email: String?,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User) =
            MemberResponse(
                id = user.id,
                loginId = user.loginId,
                displayName = user.displayName,
                avatarUrl = ImageStorage.urlOf(user.avatarKey),
                role = user.role,
                status = user.status,
                authSource = user.authSource,
                email = user.email,
                lastLoginAt = user.lastLoginAt,
                createdAt = user.createdAt,
            )
    }
}

data class CreateMemberRequest(
    val loginId: String,
    val password: String,
    val displayName: String? = null,
    val role: UserRole = UserRole.MEMBER,
)

data class UpdateMemberRequest(
    val role: UserRole? = null,
    val status: UserStatus? = null,
)

data class ResetPasswordRequest(
    val password: String,
)

/** Active people, for screens where someone picks a person (project members, assignees). */
@RestController
@RequestMapping("/api/v1/users")
class UserDirectoryController(
    private val userService: UserService,
) {
    @GetMapping
    suspend fun users(): List<UserSummaryResponse> = userService.activeUsers().map(UserSummaryResponse::from)
}

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/members")
class AdminMemberController(
    private val memberAdminService: MemberAdminService,
) {
    @GetMapping
    suspend fun list(): List<MemberResponse> = memberAdminService.list().map(MemberResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal actor: AuthenticatedUser,
        @RequestBody body: CreateMemberRequest,
        exchange: ServerWebExchange,
    ): MemberResponse =
        MemberResponse.from(
            memberAdminService.create(actor, body.loginId, body.password, body.displayName, body.role, ClientInfo.from(exchange)),
        )

    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal actor: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: UpdateMemberRequest,
        exchange: ServerWebExchange,
    ): MemberResponse = MemberResponse.from(memberAdminService.update(actor, id, body.role, body.status, ClientInfo.from(exchange)))

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun resetPassword(
        @AuthenticationPrincipal actor: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: ResetPasswordRequest,
        exchange: ServerWebExchange,
    ) {
        memberAdminService.resetPassword(actor, id, body.password, ClientInfo.from(exchange))
    }
}
