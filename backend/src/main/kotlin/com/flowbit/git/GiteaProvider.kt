package com.flowbit.git

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Gitea and Forgejo (docs/features/git-integration.md "Git 서버에 등록하기"). The body is signed
 * with HMAC-SHA256 in `X-Gitea-Signature` (Forgejo also sends `X-Forgejo-Signature`); the event is
 * in `X-Gitea-Event`: `push`, or `pull_request` for every pull request change (opened, closed,
 * edited, synchronized, assigned…; the action is in the body).
 */
@Component
class GiteaProvider(
    private val jsonMapper: JsonMapper,
) : GitProvider {
    override val type = GitProviderType.GITEA

    override fun eventName(headers: HttpHeaders): String? = headers.getFirst("X-Gitea-Event") ?: headers.getFirst("X-Forgejo-Event")

    override fun verify(
        headers: HttpHeaders,
        body: ByteArray,
        secret: String,
    ): Boolean {
        val signature = headers.getFirst("X-Gitea-Signature") ?: headers.getFirst("X-Forgejo-Signature") ?: return false
        return GitSignatures.matches(GitSignatures.hmacSha256Hex(body, secret), signature.trim().lowercase())
    }

    override fun parse(
        headers: HttpHeaders,
        body: ByteArray,
    ): List<GitEvent> {
        val payloads = ForgePayloads(branchPath = "src/branch")
        return when (eventName(headers)) {
            "push" -> listOf(payloads.push(read(body)))
            "pull_request" -> listOf(payloads.pullRequest(read(body)))
            else -> emptyList()
        }
    }

    private fun read(body: ByteArray): JsonNode = readJson(jsonMapper, body)
}
