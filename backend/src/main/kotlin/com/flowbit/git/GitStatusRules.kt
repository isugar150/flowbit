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
 * Where a stage sends an issue (D-158, D-241): to one of the categories, or one step on from wherever it is.
 * [BACKLOG] is a name so that a request naming it can be answered with a validation error, but it is
 * not a [GitStatusRules.TARGETS]: the backlog is where issues start, not where Git sends them.
 */
enum class GitMove(
    val category: IssueStatus?,
) {
    BACKLOG(IssueStatus.BACKLOG),
    READY(IssueStatus.READY),
    IN_PROGRESS(IssueStatus.IN_PROGRESS),
    DONE(IssueStatus.DONE),
    CANCELED(IssueStatus.CANCELED),

    /**
     * The status after the issue's own one, in the order of the status list: with the review steps
     * between "진행 중" and "완료", a finished piece of work goes to "리뷰 준비됨", and the next one to "리뷰 중".
     * It has no category: the category follows from the status it lands on.
     */
    NEXT(null),
}

/**
 * The status each stage moves an issue to, per connection (D-158); null leaves it alone. Moves
 * only go forward (backlog → ready → in progress → done or canceled, and each step of the status
 * list for [GitMove.NEXT]), and a finished or canceled issue is never moved, so an event that comes
 * late or again cannot undo someone's work.
 */
data class GitStatusRules(
    val rules: Map<GitStage, GitMove?>,
) {
    fun target(stage: GitStage): GitMove? = if (stage in rules) rules[stage] else DEFAULTS[stage]

    /** Every stage, the defaults filled in, for the API and the database. */
    fun all(): Map<GitStage, GitMove?> = GitStage.entries.associateWith(::target)

    fun toJson(): String = MAPPER.writeValueAsString(all().mapKeys { it.key.name }.mapValues { it.value?.name })

    companion object {
        val DEFAULTS: Map<GitStage, GitMove?> =
            mapOf(
                GitStage.BRANCH to GitMove.IN_PROGRESS,
                GitStage.PR_DRAFT to GitMove.IN_PROGRESS,
                GitStage.PR_OPEN to GitMove.IN_PROGRESS,
                GitStage.PR_MERGED to GitMove.DONE,
                GitStage.PR_CLOSED to null,
                // "Fixes" pushed to the default branch hands the work on to the next step, which may be a review (D-241).
                GitStage.CLOSING_COMMIT to GitMove.NEXT,
            )

        /** What a stage may move an issue to: the backlog is where issues start, not where Git sends them. */
        val TARGETS = setOf(GitMove.READY, GitMove.IN_PROGRESS, GitMove.DONE, GitMove.CANCELED, GitMove.NEXT)

        private val MAPPER = JsonMapper.builder().build()

        /** The two switches connections had before stages (`autoInProgress`, `autoDone`). */
        fun of(
            autoInProgress: Boolean,
            autoDone: Boolean,
        ) = GitStatusRules(
            DEFAULTS.mapValues { (stage, move) ->
                when (stage) {
                    GitStage.BRANCH, GitStage.PR_DRAFT, GitStage.PR_OPEN -> move.takeIf { autoInProgress }
                    GitStage.PR_MERGED, GitStage.CLOSING_COMMIT -> move.takeIf { autoDone }
                    GitStage.PR_CLOSED -> move
                }
            },
        )

        /** As stored; anything it does not name, or names wrongly, takes the default. */
        fun parse(json: String?): GitStatusRules {
            val node = json?.let { runCatching { MAPPER.readTree(it) }.getOrNull() } ?: return GitStatusRules(emptyMap())
            val rules = mutableMapOf<GitStage, GitMove?>()
            for (stage in GitStage.entries) {
                val value = node.get(stage.name) ?: continue
                if (value.isNull) {
                    rules[stage] = null
                } else {
                    GitMove.entries
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
