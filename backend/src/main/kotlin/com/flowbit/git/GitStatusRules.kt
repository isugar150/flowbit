package com.flowbit.git

import com.flowbit.issue.domain.IssueStatus
import tools.jackson.databind.json.JsonMapper

/** What happens on the Git server that a connection can move an issue on (D-158). */
enum class GitStage {
    /** A branch with the key is pushed for the first time, or a new commit names the issue (D-209). */
    BRANCH,

    /** A draft pull request that closes the issue is opened. */
    PR_DRAFT,

    /** A closing pull request is opened ready for review, made ready from a draft, or reopened. */
    PR_OPEN,

    /** A closing pull request is merged. */
    PR_MERGED,

    /** A closing pull request is closed without being merged. */
    PR_CLOSED,

    /** A commit pushed straight to the default branch says "Fixes FB-12". */
    CLOSING_COMMIT,
}

/**
 * The status each stage moves an issue to, per connection (D-158); null leaves it alone. Moves
 * only go forward (backlog → ready → in progress → done or canceled), and a finished or canceled
 * issue is never moved, so an event that comes late or again cannot undo someone's work.
 */
data class GitStatusRules(
    val rules: Map<GitStage, IssueStatus?>,
) {
    fun target(stage: GitStage): IssueStatus? = if (stage in rules) rules[stage] else DEFAULTS[stage]

    /** Every stage, the defaults filled in, for the API and the database. */
    fun all(): Map<GitStage, IssueStatus?> = GitStage.entries.associateWith(::target)

    fun toJson(): String = MAPPER.writeValueAsString(all().mapKeys { it.key.name }.mapValues { it.value?.name })

    companion object {
        val DEFAULTS: Map<GitStage, IssueStatus?> =
            mapOf(
                GitStage.BRANCH to IssueStatus.IN_PROGRESS,
                GitStage.PR_DRAFT to IssueStatus.IN_PROGRESS,
                GitStage.PR_OPEN to IssueStatus.IN_PROGRESS,
                GitStage.PR_MERGED to IssueStatus.DONE,
                GitStage.PR_CLOSED to null,
                GitStage.CLOSING_COMMIT to IssueStatus.DONE,
            )

        /** What a stage may move an issue to: the backlog is where issues start, not where Git sends them. */
        val TARGETS = setOf(IssueStatus.READY, IssueStatus.IN_PROGRESS, IssueStatus.DONE, IssueStatus.CANCELED)

        private val MAPPER = JsonMapper.builder().build()

        /** The two switches connections had before stages (`autoInProgress`, `autoDone`). */
        fun of(
            autoInProgress: Boolean,
            autoDone: Boolean,
        ) = GitStatusRules(
            DEFAULTS.mapValues { (stage, status) ->
                when (stage) {
                    GitStage.BRANCH, GitStage.PR_DRAFT, GitStage.PR_OPEN -> status.takeIf { autoInProgress }
                    GitStage.PR_MERGED, GitStage.CLOSING_COMMIT -> status.takeIf { autoDone }
                    GitStage.PR_CLOSED -> status
                }
            },
        )

        /** As stored; anything it does not name, or names wrongly, takes the default. */
        fun parse(json: String?): GitStatusRules {
            val node = json?.let { runCatching { MAPPER.readTree(it) }.getOrNull() } ?: return GitStatusRules(emptyMap())
            val rules = mutableMapOf<GitStage, IssueStatus?>()
            for (stage in GitStage.entries) {
                val value = node.get(stage.name) ?: continue
                if (value.isNull) {
                    rules[stage] = null
                } else {
                    IssueStatus.entries
                        .find { it.name == value.asString() }
                        ?.takeIf { it in TARGETS }
                        ?.let { rules[stage] = it }
                }
            }
            return GitStatusRules(rules)
        }

        private val RANK =
            mapOf(
                IssueStatus.BACKLOG to 0,
                IssueStatus.READY to 1,
                IssueStatus.IN_PROGRESS to 2,
                IssueStatus.DONE to 3,
                IssueStatus.CANCELED to 3,
            )

        /** The statuses an issue may be moved to [target] from: the ones before it, never done or canceled. */
        fun movableFrom(target: IssueStatus): Set<IssueStatus> {
            val rank = RANK.getValue(target)
            return RANK.filter { (status, it) -> it < rank && status != IssueStatus.DONE && status != IssueStatus.CANCELED }.keys
        }
    }
}
