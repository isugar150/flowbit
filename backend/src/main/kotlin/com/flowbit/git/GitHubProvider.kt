package com.flowbit.git

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * GitHub, as a GitHub App's webhook (docs/features/git-integration.md "GitHub: GitHub App 으로
 * 연결"). The body is signed in `X-Hub-Signature-256: sha256=<HMAC-SHA256 hex>` with the app's
 * webhook secret; the event is in `X-GitHub-Event`. `ping` (sent once when the app is made) and
 * the installation events carry nothing to link; the webhook endpoint uses the latter to read the
 * installation again.
 */
@Component
class GitHubProvider(
    private val jsonMapper: JsonMapper,
) : GitProvider {
    override val type = GitProviderType.GITHUB

    override fun eventName(headers: HttpHeaders): String? = headers.getFirst("X-GitHub-Event")

    override fun verify(
        headers: HttpHeaders,
        body: ByteArray,
        secret: String,
    ): Boolean {
        val signature = headers.getFirst("X-Hub-Signature-256")?.trim()?.lowercase() ?: return false
        return GitSignatures.matches("sha256=" + GitSignatures.hmacSha256Hex(body, secret), signature)
    }

    override fun parse(
        headers: HttpHeaders,
        body: ByteArray,
    ): List<GitEvent> {
        val payloads = ForgePayloads(branchPath = "tree")
        return when (eventName(headers)) {
            "push" -> listOf(payloads.push(readJson(jsonMapper, body)))
            "pull_request" -> listOf(payloads.pullRequest(readJson(jsonMapper, body)))
            else -> emptyList()
        }
    }

    companion object {
        /** Events about the app's installation: added or removed, or its repositories changed. */
        val INSTALLATION_EVENTS = setOf("installation", "installation_repositories")
    }
}
