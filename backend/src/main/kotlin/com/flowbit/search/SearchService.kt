package com.flowbit.search

import com.flowbit.common.error.ValidationException
import com.flowbit.instance.application.InstanceService
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class IssueMatch { KEY, TITLE, DESCRIPTION, COMMENT }

data class SearchProject(
    val id: UUID,
    val name: String,
)

data class SearchIssueRef(
    val key: String,
    val title: String,
)

data class IssueHit(
    val id: UUID,
    val key: String,
    val number: Int,
    val title: String,
    val status: IssueStatus,
    val priority: IssuePriority,
    val project: SearchProject?,
    val match: IssueMatch,
    val snippet: String?,
    val updatedAt: Instant,
)

data class DocumentHit(
    val id: UUID,
    val title: String,
    val updatedAt: Instant,
    /** Exactly one of [issue] and [project] is set: where the document belongs. */
    val issue: SearchIssueRef?,
    val project: SearchProject?,
    val snippet: String?,
)

/** A project, found by its name or by what is written in its overview (D-166). */
data class ProjectHit(
    val id: UUID,
    val name: String,
    val updatedAt: Instant,
    val snippet: String?,
)

/** A milestone, found by its name or by what is written in it (D-166). */
data class MilestoneHit(
    val id: UUID,
    val name: String,
    val project: SearchProject,
    val updatedAt: Instant,
    val snippet: String?,
)

data class SearchResult(
    val issues: List<IssueHit>,
    val documents: List<DocumentHit>,
    val projects: List<ProjectHit>,
    val milestones: List<MilestoneHit>,
    val issuesMore: Boolean,
    val documentsMore: Boolean,
    val projectsMore: Boolean,
    val milestonesMore: Boolean,
)

/**
 * Finds the issues, documents, projects and milestones a person may see (docs/features/search.md,
 * D-133, D-166). Every word of the query must appear; a query of Korean initial consonants only is
 * matched against the initials of titles. What is written in the editors is searched too: the body
 * of an issue, a document, a project's overview and a milestone. No index: plain ILIKE, sized for
 * one organisation's installation.
 */
@Service
class SearchService(
    private val databaseClient: DatabaseClient,
    private val instanceService: InstanceService,
) {
    suspend fun search(
        userId: UUID,
        rawQuery: String?,
        limit: Int,
    ): SearchResult {
        val query = rawQuery?.trim().orEmpty()
        if (query.length > MAX_QUERY_LENGTH) throw ValidationException("q", "TOO_LONG", "검색어는 ${MAX_QUERY_LENGTH}자 이하여야 합니다")
        if (query.isEmpty()) {
            return SearchResult(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                issuesMore = false,
                documentsMore = false,
                projectsMore = false,
                milestonesMore = false,
            )
        }
        val size = limit.coerceIn(1, MAX_LIMIT)
        val workspaceKey = instanceService.workspace().workspaceKey
        val terms = query.split(WHITESPACE).filter { it.isNotEmpty() }.take(MAX_TERMS)
        val initials = query.all { it in 'ㄱ'..'ㅎ' || it.isWhitespace() }
        val number = numberOf(query, workspaceKey)
        val issues = issues(userId, terms, initials, number, workspaceKey, size + 1)
        val documents = documents(userId, terms, initials, workspaceKey, size + 1)
        val projects = projects(userId, terms, initials, size + 1)
        val milestones = milestones(userId, terms, initials, size + 1)
        return SearchResult(
            issues.take(size),
            documents.take(size),
            projects.take(size),
            milestones.take(size),
            issuesMore = issues.size > size,
            documentsMore = documents.size > size,
            projectsMore = projects.size > size,
            milestonesMore = milestones.size > size,
        )
    }

    private suspend fun issues(
        userId: UUID,
        terms: List<String>,
        initials: Boolean,
        number: Int?,
        workspaceKey: String,
        limit: Int,
    ): List<IssueHit> {
        val termConditions =
            terms.indices.joinToString(" and ") { index ->
                if (initials) {
                    "replace(hangul_initials(i.title), ' ', '') like :t$index"
                } else {
                    "(i.title ilike :t$index or i.description ilike :t$index or exists(" +
                        "select 1 from issue_comments c where c.issue_id = i.id and c.deleted_at is null and c.body ilike :t$index))"
                }
            }
        val titleHit = terms.indices.joinToString(" and ") { "i.title ilike :t$it" }
        val sql =
            """
            select i.id, i.number, i.title, i.status, i.priority, i.updated_at, i.description,
                   p.id as project_id, p.name as project_name,
                   ${if (initials) "null" else "(select c.body from issue_comments c where c.issue_id = i.id and c.deleted_at is null and c.body ilike :t0 order by c.created_at desc limit 1)"} as comment_hit,
                   (i.number = :number) as key_hit,
                   ${if (initials) "true" else "($titleHit)"} as title_hit
            from issues i
            left join projects p on p.id = i.project_id
            where i.deleted_at is null
              and $VISIBLE_ISSUE
              and ((i.number = :number) or ($termConditions))
            order by key_hit desc, title_hit desc, i.updated_at desc
            limit $limit
            """.trimIndent()
        var spec = databaseClient.sql(sql).bind("viewerId", userId).bind("number", number ?: -1)
        terms.forEachIndexed { index, term -> spec = spec.bind("t$index", pattern(term, initials)) }
        return spec
            .map { row ->
                val title = checkNotNull(row.get("title", String::class.java))
                val description = row.get("description", String::class.java).orEmpty()
                val comment = row.get("comment_hit", String::class.java)
                val keyHit = row.get("key_hit", Boolean::class.javaObjectType) == true
                val titleHitValue = row.get("title_hit", Boolean::class.javaObjectType) == true
                val inDescription = !initials && snippetOf(description, terms) != null
                val match =
                    when {
                        keyHit -> IssueMatch.KEY
                        titleHitValue || initials -> IssueMatch.TITLE
                        inDescription -> IssueMatch.DESCRIPTION
                        comment != null -> IssueMatch.COMMENT
                        else -> IssueMatch.DESCRIPTION
                    }
                val snippet =
                    when (match) {
                        IssueMatch.COMMENT -> snippetOf(comment.orEmpty(), terms)
                        else -> if (initials) null else snippetOf(description, terms)
                    }
                val number = checkNotNull(row.get("number", Int::class.javaObjectType))
                IssueHit(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    key = "$workspaceKey-$number",
                    number = number,
                    title = title,
                    status = IssueStatus.valueOf(checkNotNull(row.get("status", String::class.java))),
                    priority = IssuePriority.valueOf(checkNotNull(row.get("priority", String::class.java))),
                    project =
                        row.get("project_id", UUID::class.java)?.let {
                            SearchProject(it, checkNotNull(row.get("project_name", String::class.java)))
                        },
                    match = match,
                    snippet = snippet,
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    private suspend fun documents(
        userId: UUID,
        terms: List<String>,
        initials: Boolean,
        workspaceKey: String,
        limit: Int,
    ): List<DocumentHit> {
        val termConditions =
            terms.indices.joinToString(" and ") { index ->
                if (initials) {
                    "replace(hangul_initials(d.title), ' ', '') like :t$index"
                } else {
                    "(d.title ilike :t$index or b.body ilike :t$index or b.flat ilike :t$index)"
                }
            }
        val titleHit = if (initials) "true" else terms.indices.joinToString(" and ") { "d.title ilike :t$it" }
        val sql =
            """
            with recursive live(id) as (
                select id from documents where parent_id is null and deleted_at is null
                union all
                select child.id from documents child join live on child.parent_id = live.id where child.deleted_at is null
            )
            select d.id, d.title, d.updated_at, b.body, b.flat, ($titleHit) as title_hit,
                   i.number as issue_number, i.title as issue_title, p.id as project_id, p.name as project_name
            from documents d
            join live on live.id = d.id
            cross join lateral (${textOf("d")}) b
            left join issues i on i.id = d.issue_id
            left join projects ip on ip.id = i.project_id
            left join projects p on p.id = d.project_id
            where (
                (d.issue_id is not null and i.deleted_at is null and (i.project_id is null or (ip.archived_at is null and exists(
                    select 1 from project_members pm where pm.project_id = i.project_id and pm.user_id = :viewerId))))
                or (d.project_id is not null and p.archived_at is null and exists(
                    select 1 from project_members pm where pm.project_id = d.project_id and pm.user_id = :viewerId))
            )
              and $termConditions
            order by title_hit desc, d.updated_at desc
            limit $limit
            """.trimIndent()
        var spec = databaseClient.sql(sql).bind("viewerId", userId)
        terms.forEachIndexed { index, term -> spec = spec.bind("t$index", pattern(term, initials)) }
        return spec
            .map { row ->
                val issueNumber = row.get("issue_number", Int::class.javaObjectType)
                DocumentHit(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    title = checkNotNull(row.get("title", String::class.java)),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                    issue = issueNumber?.let { SearchIssueRef("$workspaceKey-$it", checkNotNull(row.get("issue_title", String::class.java))) },
                    project =
                        row.get("project_id", UUID::class.java)?.let {
                            SearchProject(it, checkNotNull(row.get("project_name", String::class.java)))
                        },
                    snippet = if (initials) null else snippetOfText(row, terms),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    /** The projects the person is a member of, found by name or by what is written in the overview. */
    private suspend fun projects(
        userId: UUID,
        terms: List<String>,
        initials: Boolean,
        limit: Int,
    ): List<ProjectHit> {
        val termConditions =
            terms.indices.joinToString(" and ") { index ->
                if (initials) {
                    "replace(hangul_initials(p.name), ' ', '') like :t$index"
                } else {
                    "(p.name ilike :t$index or b.body ilike :t$index or b.flat ilike :t$index)"
                }
            }
        val nameHit = if (initials) "true" else terms.indices.joinToString(" and ") { "p.name ilike :t$it" }
        val sql =
            """
            select p.id, p.name, p.updated_at, b.body, b.flat, ($nameHit) as title_hit
            from projects p
            cross join lateral (${textOf("p")}) b
            where p.archived_at is null
              and exists (select 1 from project_members pm where pm.project_id = p.id and pm.user_id = :viewerId)
              and $termConditions
            order by title_hit desc, p.updated_at desc
            limit $limit
            """.trimIndent()
        return bind(databaseClient.sql(sql), userId, terms, initials)
            .map { row ->
                ProjectHit(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    name = checkNotNull(row.get("name", String::class.java)),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                    snippet = if (initials) null else snippetOfText(row, terms),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    /** The milestones of the projects the person is a member of, found by name or by what is written in them. */
    private suspend fun milestones(
        userId: UUID,
        terms: List<String>,
        initials: Boolean,
        limit: Int,
    ): List<MilestoneHit> {
        val termConditions =
            terms.indices.joinToString(" and ") { index ->
                if (initials) {
                    "replace(hangul_initials(m.name), ' ', '') like :t$index"
                } else {
                    "(m.name ilike :t$index or b.body ilike :t$index or b.flat ilike :t$index)"
                }
            }
        val nameHit = if (initials) "true" else terms.indices.joinToString(" and ") { "m.name ilike :t$it" }
        val sql =
            """
            select m.id, m.name, m.updated_at, b.body, b.flat, ($nameHit) as title_hit, p.id as project_id, p.name as project_name
            from milestones m
            join projects p on p.id = m.project_id
            cross join lateral (${textOf("m")}) b
            where p.archived_at is null
              and exists (select 1 from project_members pm where pm.project_id = p.id and pm.user_id = :viewerId)
              and $termConditions
            order by title_hit desc, m.updated_at desc
            limit $limit
            """.trimIndent()
        return bind(databaseClient.sql(sql), userId, terms, initials)
            .map { row ->
                MilestoneHit(
                    id = checkNotNull(row.get("id", UUID::class.java)),
                    name = checkNotNull(row.get("name", String::class.java)),
                    project =
                        SearchProject(
                            checkNotNull(row.get("project_id", UUID::class.java)),
                            checkNotNull(row.get("project_name", String::class.java)),
                        ),
                    updatedAt = checkNotNull(row.get("updated_at", OffsetDateTime::class.java)).toInstant(),
                    snippet = if (initials) null else snippetOfText(row, terms),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    private fun bind(
        spec: DatabaseClient.GenericExecuteSpec,
        userId: UUID,
        terms: List<String>,
        initials: Boolean,
    ): DatabaseClient.GenericExecuteSpec {
        var bound = spec.bind("viewerId", userId)
        terms.forEachIndexed { index, term -> bound = bound.bind("t$index", pattern(term, initials)) }
        return bound
    }

    /** A piece of the text of the row, from the text with spaces between blocks, or else the one without (words split by bold, a link, a tag). */
    private fun snippetOfText(
        row: io.r2dbc.spi.Readable,
        terms: List<String>,
    ): String? =
        snippetOf(row.get("body", String::class.java).orEmpty(), terms)
            ?: snippetOf(row.get("flat", String::class.java).orEmpty(), terms)

    /** `FB-12`, `fb-12`, `12` or `#12`: the issue with that number. */
    private fun numberOf(
        query: String,
        workspaceKey: String,
    ): Int? {
        val match = Regex("^(?:${Regex.escape(workspaceKey)}-|#)?(\\d{1,9})$", RegexOption.IGNORE_CASE).find(query) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun pattern(
        term: String,
        initials: Boolean,
    ): String {
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%".let { if (initials) it.replace(" ", "") else it }
    }

    companion object {
        const val MAX_QUERY_LENGTH = 100
        const val MAX_LIMIT = 50
        private const val MAX_TERMS = 5
        private const val BEFORE = 30
        private const val AFTER = 90
        private val WHITESPACE = Regex("\\s+")

        /** The same rule as the issue lists (IssueQueries): no project, or a project in use the viewer is a member of (D-131). */
        private const val VISIBLE_ISSUE =
            """(i.project_id is null or (p.archived_at is null and exists(
                   select 1 from project_members pm where pm.project_id = i.project_id and pm.user_id = :viewerId)))"""

        /**
         * The words written in the editor of `alias.content`, two ways: `body` has a space between the
         * pieces of text (so that the end of one paragraph and the start of the next are not one word),
         * `flat` has none (so that a word split by bold, a link or a tag is still one word).
         */
        private fun textOf(alias: String): String =
            """
            select coalesce(string_agg(t, ' '), '') as body, coalesce(string_agg(t, ''), '') as flat
            from (select v #>> '{}' as t from jsonb_path_query($alias.content, 'strict ${'$'}.**.text') v) s
            """.trimIndent()

        /** A short piece of [text] around the first word found, on one line; null when no word is in it. */
        fun snippetOf(
            text: String,
            terms: List<String>,
        ): String? {
            val at =
                terms
                    .mapNotNull { term -> text.indexOf(term, ignoreCase = true).takeIf { it >= 0 } }
                    .minOrNull() ?: return null
            val start = (at - BEFORE).coerceAtLeast(0)
            val end = (at + AFTER).coerceAtMost(text.length)
            val piece = text.substring(start, end).replace(WHITESPACE, " ").trim()
            return (if (start > 0) "…" else "") + piece + (if (end < text.length) "…" else "")
        }
    }
}
