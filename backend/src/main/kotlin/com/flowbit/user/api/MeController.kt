package com.flowbit.user.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.user.application.UserProfile
import com.flowbit.user.application.UserService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class MeResponse(
    val id: UUID,
    val loginId: String,
    val role: String,
    val locale: String,
    val timezone: String,
) {
    companion object {
        fun from(profile: UserProfile) = MeResponse(profile.id, profile.loginId, profile.role.name, profile.locale, profile.timezone)
    }
}

@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val userService: UserService,
) {
    @GetMapping
    suspend fun me(
        @AuthenticationPrincipal principal: AuthenticatedUser,
    ): MeResponse = MeResponse.from(userService.profile(principal.id))
}
