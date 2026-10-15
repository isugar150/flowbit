package com.flowbit.git

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class GitStatusResponse(
    /** Whether a Git server is connected and switched on; without one the Git buttons stay hidden. */
    val enabled: Boolean,
)

/** For everyone signed in: whether to show what only makes sense with Git, such as "브랜치 이름 복사". */
@RestController
class GitStatusController(
    private val connections: GitConnectionService,
) {
    @GetMapping("/api/v1/git/status")
    suspend fun status(): GitStatusResponse = GitStatusResponse(connections.inUse())
}
