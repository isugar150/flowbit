package com.flowbit.git

import org.springframework.http.HttpHeaders
import java.time.Instant

/** Someone on the Git server, as an event names them. Any of the three may be missing. */
data class GitPerson(
    /** The Git user name (login). */
    val username: String?,
    val email: String?,
    /** The full name, when the server sends one. */
    val name: String?,
) {
    /** What the history shows when no Flowbit account matches; null when the event names nobody. */
    val label: String?
        get() = username ?: name ?: email
}

data class GitCommit(
    val sha: String,
    /** The whole message: subject and body. */
    val message: String,
    val url: String,
    val authorName: String?,
    val timestamp: Instant?,
)

enum class GitPrState { OPEN, DRAFT, MERGED, CLOSED }

/** What a Git server reported, in one shape whatever the server (docs/features/git-integration.md "받는 방식"). */
sealed interface GitEvent {
    /** org/web */
    val repository: String
}

/** Commits pushed to a branch (or the branch made or removed). */
data class GitPush(
    override val repository: String,
    /** Null for a push that is not to a branch (a tag). */
    val branch: String?,
    val defaultBranch: String?,
    /** The branch was removed by this push. */
    val deleted: Boolean,
    val branchUrl: String?,
    val pusher: GitPerson,
    /** Oldest first, as the server sends them. */
    val commits: List<GitCommit>,
) : GitEvent {
    val toDefaultBranch: Boolean
        get() = branch != null && branch == defaultBranch
}

/** A pull request (merge request) was opened, changed, merged or closed. */
data class GitPullRequest(
    override val repository: String,
    /** The server's own word for what happened: opened, reopened, closed, edited, synchronized… */
    val action: String,
    val number: Int,
    val title: String,
    val body: String,
    val url: String,
    /** The state after this event. */
    val state: GitPrState,
    /** The branch it comes from. */
    val headBranch: String?,
    /** Who opened it. */
    val author: GitPerson,
    /** Who did what this event reports: who merged it, or who sent the event. */
    val actor: GitPerson,
) : GitEvent

/**
 * One kind of Git server. To add one, implement this as a Spring bean; the webhook endpoint picks
 * it up by [type].
 */
interface GitProvider {
    val type: GitProviderType

    /** The name of the event, as the server's event header says it; null when the header is missing. */
    fun eventName(headers: HttpHeaders): String?

    /** Whether the call was signed with [secret]. Compares in constant time. */
    fun verify(
        headers: HttpHeaders,
        body: ByteArray,
        secret: String,
    ): Boolean

    /**
     * The events in the call. Empty for an event Flowbit does not use, which is answered 200 and
     * otherwise ignored. Throws [GitPayloadException] for a body it cannot read.
     */
    fun parse(
        headers: HttpHeaders,
        body: ByteArray,
    ): List<GitEvent>
}

class GitPayloadException(
    message: String,
) : RuntimeException(message)
