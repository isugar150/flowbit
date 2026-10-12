package com.flowbit.user.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.media.api.UploadReader
import com.flowbit.media.application.ImageStorage
import com.flowbit.user.application.UserProfile
import com.flowbit.user.application.UserService
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class MeResponse(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val role: String,
    val locale: String,
    val timezone: String,
) {
    companion object {
        fun from(profile: UserProfile) =
            MeResponse(
                id = profile.id,
                loginId = profile.loginId,
                displayName = profile.displayName,
                avatarUrl = ImageStorage.urlOf(profile.avatarKey),
                role = profile.role.name,
                locale = profile.locale,
                timezone = profile.timezone,
            )
    }
}

data class UpdateMeRequest(
    val displayName: String?,
)

@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val userService: UserService,
    private val uploadReader: UploadReader,
) {
    @GetMapping
    suspend fun me(
        @AuthenticationPrincipal principal: AuthenticatedUser,
    ): MeResponse = MeResponse.from(userService.profile(principal.id))

    @PatchMapping
    suspend fun update(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @RequestBody body: UpdateMeRequest,
    ): MeResponse = MeResponse.from(userService.updateDisplayName(principal.id, body.displayName))

    @PutMapping("/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun replaceAvatar(
        @AuthenticationPrincipal principal: AuthenticatedUser,
        @RequestPart("file") file: FilePart,
    ): MeResponse = MeResponse.from(userService.replaceAvatar(principal.id, uploadReader.read(file)))

    @DeleteMapping("/avatar")
    suspend fun removeAvatar(
        @AuthenticationPrincipal principal: AuthenticatedUser,
    ): MeResponse = MeResponse.from(userService.removeAvatar(principal.id))
}
