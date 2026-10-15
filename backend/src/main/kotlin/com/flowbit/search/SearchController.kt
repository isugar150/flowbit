package com.flowbit.search

import com.flowbit.auth.application.AuthenticatedUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Issues and documents the signed-in person may see (docs/features/search.md). */
@RestController
@RequestMapping("/api/v1/search")
class SearchController(
    private val service: SearchService,
) {
    @GetMapping
    suspend fun search(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): SearchResult = service.search(user.id, q, limit)
}
