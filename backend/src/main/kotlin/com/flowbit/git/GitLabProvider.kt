package com.flowbit.git

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * GitLab (docs/features/git-integration.md "Git 서버에 등록하기"). GitLab does not sign the body:
 * it sends the secret token as it is in `X-Gitlab-Token`. The event is in `X-Gitlab-Event`:
 * `Push Hook` or `Merge Request Hook` (what happened to the merge request is
 * `object_attributes.action`). A merge request is shown as `!iid`, but stored as a pull request
 * numbered by its iid like the other servers'.
 */
@Component
class GitLabProvider(
    private val jsonMapper: JsonMapper,
) : GitProvider {
    override val type = GitProviderType.GITLAB

    override fun eventName(headers: HttpHeaders): String? = headers.getFirst("X-Gitlab-Event")

    override fun verify(
        headers: HttpHeaders,
        body: ByteArray,
        secret: String,
    ): Boolean {
        val token = headers.getFirst("X-Gitlab-Token") ?: return false
        return GitSignatures.matches(secret, token)
    }

    override fun parse(
        headers: HttpHeaders,
        body: ByteArray,
    ): List<GitEvent> =
        when (eventName(headers)) {
            "Push Hook" -> listOf(push(readJson(jsonMapper, body)))
            "Merge Request Hook" -> listOf(mergeRequest(readJson(jsonMapper, body)))
            else -> emptyList()
        }

    private fun push(node: JsonNode): GitPush {
        val project = node.path("project")
        val ref = text(node, "ref").orEmpty()
        val branch = ref.removePrefix("refs/heads/").takeIf { ref.startsWith("refs/heads/") }
        val webUrl = text(project, "web_url")
        return GitPush(
            repository = repository(project),
            branch = branch,
            defaultBranch = text(project, "default_branch"),
            // A removed branch arrives as a push whose new head is all zeros.
            deleted = text(node, "after")?.all { it == '0' } == true,
            branchUrl = if (branch != null && webUrl != null) "$webUrl/-/tree/$branch" else null,
            pusher = GitPerson(username = text(node, "user_username"), email = email(text(node, "user_email")), name = text(node, "user_name")),
            commits =
                items(node.path("commits")).mapNotNull { commit ->
                    val sha = text(commit, "id") ?: return@mapNotNull null
                    GitCommit(
                        sha = sha,
                        message = text(commit, "message").orEmpty(),
                        url = text(commit, "url").orEmpty(),
                        authorName = text(commit.path("author"), "name"),
                        timestamp = instant(text(commit, "timestamp")),
                    )
                },
        )
    }

    private fun mergeRequest(node: JsonNode): GitPullRequest {
        val attributes = node.path("object_attributes")
        if (attributes.isMissingNode || attributes.isNull) throw GitPayloadException("object_attributes 가 없습니다")
        val title = text(attributes, "title").orEmpty()
        val state =
            when (text(attributes, "state")) {
                "merged" -> GitPrState.MERGED
                "closed", "locked" -> GitPrState.CLOSED
                else ->
                    if (attributes.path("draft").asBoolean(false) || attributes.path("work_in_progress").asBoolean(false)) {
                        GitPrState.DRAFT
                    } else {
                        GitPrState.OPEN
                    }
            }
        // The person who caused the event; the payload names the author and the merger only by id.
        val user = node.path("user")
        val person = GitPerson(username = text(user, "username"), email = email(text(user, "email")), name = text(user, "name"))
        return GitPullRequest(
            repository = repository(node.path("project")),
            action = ACTIONS[text(attributes, "action")] ?: text(attributes, "action").orEmpty(),
            number = attributes.path("iid").asInt(0).takeIf { it > 0 } ?: throw GitPayloadException("MR 번호(iid)가 없습니다"),
            title = title,
            body = text(attributes, "description").orEmpty(),
            url = text(attributes, "url").orEmpty(),
            state = state,
            headBranch = text(attributes, "source_branch"),
            author = person,
            actor = person,
        )
    }

    private fun repository(project: JsonNode): String =
        text(project, "path_with_namespace") ?: throw GitPayloadException("저장소 이름(project.path_with_namespace)이 없습니다")

    /** GitLab may hide the address as `[REDACTED]`. */
    private fun email(value: String?): String? = value?.takeIf { "@" in it }

    private companion object {
        /** GitLab's words for what happened, in the words the other servers use. */
        val ACTIONS = mapOf("open" to "opened", "reopen" to "reopened", "close" to "closed", "merge" to "closed", "update" to "edited")
    }
}
