package com.flowbit.git

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    private fun read(body: ByteArray): JsonNode =
        try {
            jsonMapper.readTree(body)
        } catch (_: JacksonException) {
            throw GitPayloadException("본문이 JSON 이 아닙니다")
        }
}

/** Signing as Gitea, GitHub and the like do it. */
object GitSignatures {
    fun hmacSha256Hex(
        body: ByteArray,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(body))
    }

    /** Equal strings, compared in time that does not depend on where they differ. */
    fun matches(
        expected: String,
        given: String,
    ): Boolean = MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
}

/**
 * Reads the push and pull request bodies of Gitea, which copied GitHub's: the same field names,
 * apart from a few that one of them leaves out. [branchPath] is where the server shows a branch
 * under the repository's address (`src/branch` on Gitea, `tree` on GitHub).
 */
class ForgePayloads(
    private val branchPath: String,
) {
    fun push(node: JsonNode): GitPush {
        val repository = repository(node)
        val ref = text(node, "ref").orEmpty()
        val branch = ref.removePrefix("refs/heads/").takeIf { ref.startsWith("refs/heads/") }
        val htmlUrl = text(node.path("repository"), "html_url")
        val deleted = node.path("deleted").asBoolean(false) || text(node, "after")?.all { it == '0' } == true
        val pusher = node.path("pusher")
        return GitPush(
            repository = repository,
            branch = branch,
            defaultBranch = text(node.path("repository"), "default_branch"),
            deleted = deleted,
            branchUrl = if (branch != null && htmlUrl != null) "$htmlUrl/$branchPath/$branch" else null,
            // Gitea names the pusher by login; GitHub only by name (which is the login there).
            pusher =
                GitPerson(
                    username = text(pusher, "login") ?: text(pusher, "username") ?: text(pusher, "name"),
                    email = text(pusher, "email"),
                    name = text(pusher, "full_name"),
                ),
            commits =
                items(node.path("commits")).mapNotNull { commit ->
                    val sha = text(commit, "id") ?: return@mapNotNull null
                    GitCommit(
                        sha = sha,
                        message = text(commit, "message").orEmpty(),
                        url = text(commit, "url").orEmpty(),
                        authorName = text(commit.path("author"), "username") ?: text(commit.path("author"), "name"),
                        timestamp = text(commit, "timestamp")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                    )
                },
        )
    }

    fun pullRequest(node: JsonNode): GitPullRequest {
        val pr = node.path("pull_request")
        if (pr.isMissingNode || pr.isNull) throw GitPayloadException("pull_request 가 없습니다")
        val title = text(pr, "title").orEmpty()
        val merged = pr.path("merged").asBoolean(false)
        val state =
            when {
                merged -> GitPrState.MERGED
                text(pr, "state") == "closed" -> GitPrState.CLOSED
                pr.path("draft").asBoolean(false) || WIP_PREFIXES.any { title.lowercase().startsWith(it) } -> GitPrState.DRAFT
                else -> GitPrState.OPEN
            }
        val author = person(pr.path("user"))
        val sender = person(node.path("sender"))
        val mergedBy = person(pr.path("merged_by"))
        return GitPullRequest(
            repository = repository(node),
            action = text(node, "action").orEmpty(),
            number = pr.path("number").asInt(node.path("number").asInt(0)).takeIf { it > 0 } ?: throw GitPayloadException("PR 번호가 없습니다"),
            title = title,
            body = text(pr, "body").orEmpty(),
            url = text(pr, "html_url") ?: text(pr, "url").orEmpty(),
            state = state,
            headBranch = text(pr.path("head"), "ref"),
            author = author,
            actor =
                if (merged && mergedBy.username != null) {
                    mergedBy
                } else if (sender.username != null) {
                    sender
                } else {
                    author
                },
        )
    }

    private fun repository(node: JsonNode): String =
        text(node.path("repository"), "full_name") ?: throw GitPayloadException("저장소 이름(repository.full_name)이 없습니다")

    private fun person(node: JsonNode) =
        GitPerson(username = text(node, "login") ?: text(node, "username"), email = text(node, "email"), name = text(node, "full_name"))

    private fun items(node: JsonNode): List<JsonNode> = (0 until node.size()).map { node.get(it) }

    private fun text(
        node: JsonNode,
        name: String,
    ): String? =
        node
            .get(name)
            ?.takeIf { it.isValueNode && !it.isNull }
            ?.asString()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        /** Gitea marks a draft by the start of its title. */
        val WIP_PREFIXES = listOf("wip:", "[wip]")
    }
}
