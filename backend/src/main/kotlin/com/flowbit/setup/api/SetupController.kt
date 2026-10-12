package com.flowbit.setup.api

import com.flowbit.auth.api.SessionCookies
import com.flowbit.auth.application.AuthProperties
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.common.web.ClientInfo
import com.flowbit.setup.application.SetupService
import com.flowbit.user.api.MeResponse
import com.flowbit.user.application.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

data class SetupStatusResponse(
    val completed: Boolean,
)

data class SetupRequest(
    val loginId: String,
    val password: String,
)

@RestController
@RequestMapping("/api/v1/setup")
class SetupController(
    private val setupService: SetupService,
    private val userService: UserService,
    private val sessionCookies: SessionCookies,
    private val rateLimiter: RateLimiter,
    private val properties: AuthProperties,
) {
    @GetMapping("/status")
    suspend fun status(): SetupStatusResponse = SetupStatusResponse(setupService.isCompleted())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun setup(
        @RequestBody body: SetupRequest,
        exchange: ServerWebExchange,
    ): MeResponse {
        val client = ClientInfo.from(exchange)
        rateLimiter.check("setup", client.ipAddress, properties.rateLimitPerMinute)
        val result = setupService.complete(body.loginId, body.password, client)
        sessionCookies.write(exchange, result.sessionToken)
        return MeResponse.from(userService.profileOf(result.admin))
    }
}
