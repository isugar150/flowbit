package com.flowbit.git

import com.flowbit.instance.application.InstanceService
import org.springframework.stereotype.Component

/**
 * Issue keys in text, for the current workspace key (docs/features/git-integration.md "티켓 키
 * 인식"): `FB-12` in any case, not inside a longer word (`xfb-12` and `fb-12x` are not keys,
 * `feature/fb-12-login` and `[FB-12]` are). The patterns are made again when the key changes; a
 * key used before then is no longer recognized.
 */
@Component
class IssueKeyMatcher(
    private val instanceService: InstanceService,
) {
    @Volatile
    private var current: IssueKeys? = null

    suspend fun keys(): IssueKeys {
        val prefix = instanceService.workspace().workspaceKey
        return current?.takeIf { it.prefix == prefix } ?: IssueKeys(prefix).also { current = it }
    }
}

/** Finds the keys of one workspace key, such as FB. */
class IssueKeys(
    val prefix: String,
) {
    private val key = Regex("""(?<![\p{L}\p{N}])${Regex.escape(prefix)}-(\d{1,9})(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

    /** "Fixes FB-12", "closed: FB-12", "resolves FB-12" and the other forms of the three words. */
    private val closing =
        Regex(
            """(?<![\p{L}\p{N}])(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s+${Regex.escape(prefix)}-(\d{1,9})(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE,
        )

    /** The issue numbers named in [text], in the order they first appear. */
    fun numbers(text: String?): List<Int> = text?.let { found(key, it) }.orEmpty()

    /** The issue numbers [text] says it finishes, with a word such as "Fixes" right before the key. */
    fun closingNumbers(text: String?): List<Int> = text?.let { found(closing, it) }.orEmpty()

    fun keyOf(number: Int) = "$prefix-$number"

    private fun found(
        pattern: Regex,
        text: String,
    ): List<Int> =
        pattern
            .findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .toList()
}
