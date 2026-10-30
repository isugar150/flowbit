package com.flowbit.mcp

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.error.ForbiddenException
import com.flowbit.common.error.ValidationException
import com.flowbit.content.ContentFeedService
import com.flowbit.content.ContentTarget
import com.flowbit.content.ContentTargetType
import com.flowbit.content.ContentThreadService
import com.flowbit.customproperty.application.CustomPropertyChange
import com.flowbit.customproperty.application.CustomPropertyOwnerKind
import com.flowbit.customproperty.application.CustomPropertyService
import com.flowbit.customproperty.application.CustomPropertyType
import com.flowbit.customproperty.application.ProjectIssueFieldService
import com.flowbit.cycle.CycleService
import com.flowbit.issue.api.CommentResponse
import com.flowbit.issue.api.CreateIssueRequest
import com.flowbit.issue.api.DocumentResponse
import com.flowbit.issue.api.IssueAnnouncer
import com.flowbit.issue.api.IssueRelationResponse
import com.flowbit.issue.api.IssueResponse
import com.flowbit.issue.application.IssueChange
import com.flowbit.issue.application.IssueCommentService
import com.flowbit.issue.application.IssueDocumentService
import com.flowbit.issue.application.IssuePatch
import com.flowbit.issue.application.IssueRelations
import com.flowbit.issue.application.IssueService
import com.flowbit.issue.application.IssueStatuses
import com.flowbit.issue.application.RelationKind
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.label.LabelResponse
import com.flowbit.label.LabelService
import com.flowbit.media.application.ImageStorage
import com.flowbit.project.application.ProjectService
import com.flowbit.realtime.RealtimeHub
import com.flowbit.search.SearchService
import com.flowbit.user.api.UserSummaryResponse
import com.flowbit.user.application.UserService
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The integration adapter uses the same use cases and post-commit announcements as the page controllers. */
@Service
class McpOperations(
    private val issues: IssueService,
    private val comments: IssueCommentService,
    private val announcer: IssueAnnouncer,
    private val projects: ProjectService,
    private val users: UserService,
    private val statuses: IssueStatuses,
    private val labels: LabelService,
    private val fields: ProjectIssueFieldService,
    private val properties: CustomPropertyService,
    private val cycles: CycleService,
    private val relations: IssueRelations,
    private val search: SearchService,
    private val hub: RealtimeHub,
    private val markdown: McpMarkdown,
    private val json: JsonMapper,
    private val files: McpFiles,
    private val fileReader: McpFileReader,
    private val documents: IssueDocumentService,
    private val threads: ContentThreadService,
    private val feeds: ContentFeedService,
    private val workLogs: McpWorkLogs,
) {
    suspend fun execute(
        identity: TokenIdentity,
        name: String,
        args: Map<String, Any?>,
    ): Any {
        val tool = McpToolDefinitions.all.firstOrNull { it.name == name } ?: throw ValidationException("name", "UNKNOWN_TOOL", "지원하지 않는 도구입니다")
        if (tool.annotations["readOnlyHint"] != true && identity.scope != TokenScope.WRITE) throw ForbiddenException("읽기 전용 토큰으로 변경할 수 없습니다")
        McpToolDefinitions.validate(tool.inputSchema, args)
        val user = identity.user

        fun number(key: String = "issue") = issueNumber(args[key])

        fun uuid(key: String): UUID =
            runCatching { UUID.fromString(args[key] as String) }.getOrElse { throw ValidationException(key, "INVALID", "올바른 ID를 지정하세요") }

        fun text(key: String) = args[key] as? String ?: throw ValidationException(key, "REQUIRED", "값을 입력하세요")

        fun objectAt(key: String): Map<String, Any?> =
            (args[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: throw ValidationException(key, "INVALID", "객체를 입력하세요")

        fun attachmentIds(): List<UUID>? =
            (args["attachmentIds"] as? List<*>)?.map { value ->
                runCatching { UUID.fromString(value as String) }.getOrElse { throw ValidationException("attachmentIds", "INVALID", "올바른 첨부 ID를 지정하세요") }
            }
        return when (name) {
            "list_work_logs", "search_comments" -> workLogs.query(user, args, name == "search_comments")
            "list_issue_files" -> files.list(user, number())
            "read_file", "render_file_page" ->
                fileReader.read(
                    user,
                    number(),
                    text("fileId"),
                    args["mode"] as? String ?: "AUTO",
                    (args["offset"] as? Number)?.toInt() ?: 0,
                    (args["limit"] as? Number)?.toInt() ?: 20_000,
                    (args["page"] as? Number)?.toInt() ?: 1,
                    name == "render_file_page",
                )
            "attach_file" ->
                files.attachBase64(
                    user,
                    number(),
                    text("fileName"),
                    text("data"),
                    (args["placement"] as? String)?.let(McpFilePlacement::valueOf) ?: McpFilePlacement.ATTACHMENT,
                    args["image"] == true,
                    args["markdown"] as? String,
                )
            "get_document" -> {
                val document = documents.document(user, uuid("documentId"))
                mapOf(
                    "document" to DocumentResponse.from(document),
                    "markdown" to markdown.render(document.content),
                    "feed" to feeds.feed(user, ContentTarget(ContentTargetType.DOCUMENT, document.document.id)),
                    "files" to document.issue?.number?.let { files.list(user, it) },
                )
            }
            "list_projects" ->
                page(
                    projects.list(user).filter { it.myRole != null && matches(it.name, args) }.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "myRole" to it.myRole,
                            "canCreate" to it.myRole?.canEdit,
                            "triageEnabled" to it.triageEnabled,
                            "estimatesEnabled" to it.estimatesEnabled,
                        )
                    },
                    args,
                )
            "list_statuses" -> statuses.all()
            "list_people" -> people(user, args)
            "get_issue_options" -> options(user, args)
            "list_issues" ->
                page(
                    issues
                        .list(user, args["projectId"] as? String, args["deleted"] == true, 10_000)
                        .filter {
                            matches("${it.key} ${it.title}", args)
                        }.map(IssueResponse::from),
                    args,
                )
            "search_issues" -> search.search(user.id, text("q"), limit(args)).let { mapOf("items" to it.issues, "hasMore" to it.issuesMore) }
            "get_issue" -> detail(user, number())
            "create_issue" -> {
                val values = body(objectAt("fields"))
                val input =
                    runCatching {
                        json.convertValue(values, CreateIssueRequest::class.java)
                    }.getOrElse { throw ValidationException("fields", "INVALID", "생성 필드의 형식을 확인하세요") }
                val change = issues.create(user, input.toNewIssue())
                announce(user, change)
                detail(user, change.issue.number)
            }
            "update_issue" -> {
                val change = issues.update(user, number(), IssuePatch(body(objectAt("fields"))))
                announce(user, change)
                detail(user, change.issue.number)
            }
            "delete_issue", "restore_issue" -> {
                val change = if (name == "delete_issue") issues.delete(user, number()) else issues.restore(user, number())
                announce(user, change)
                mapOf("issue" to IssueResponse.from(change.issue), "deleted" to (change.issue.deletedAt != null))
            }
            "list_comments" ->
                comments.activity(user, number()).let {
                    mapOf(
                        "comments" to it.comments.map { c -> mapOf("comment" to CommentResponse.from(c), "markdown" to markdown.render(c.content)) },
                        "activity" to it.items,
                    )
                }
            "create_comment", "update_comment" -> {
                val content = body(args)["content"]
                val attachments = attachmentIds()
                if (content == null && attachments.isNullOrEmpty()) throw ValidationException("markdown", "REQUIRED", "댓글 내용이나 첨부파일을 지정하세요")
                val result =
                    if (name ==
                        "create_comment"
                    ) {
                        comments.addComment(
                            user,
                            number(),
                            content,
                            attachmentIds = attachments.orEmpty(),
                            parentId = args["parentId"]?.let { uuid("parentId") },
                        )
                    } else {
                        comments.updateComment(user, uuid("commentId"), content, attachmentIds = attachments)
                    }
                announcer.commented(user, result.change)
                CommentResponse.from(result.comment)
            }
            "delete_comment" -> {
                val issue = comments.removeComment(user, uuid("commentId"))
                announcer.commentsChanged(user, issue)
                mapOf("deleted" to true)
            }
            "list_custom_properties" -> properties.list(user, CustomPropertyOwnerKind.ISSUE, issues.get(user, number()).issue.id)
            "create_custom_property" ->
                announceProperty(
                    user,
                    properties.create(
                        user,
                        CustomPropertyOwnerKind.ISSUE,
                        issues.get(user, number()).issue.id,
                        text("label"),
                        CustomPropertyType.valueOf(text("type")),
                        args["value"],
                    ),
                )
            "update_custom_property" -> announceProperty(user, properties.update(user, uuid("propertyId"), objectAt("fields")))
            "delete_custom_property" -> announceProperty(user, properties.delete(user, uuid("propertyId")))
            "set_project_field" -> announceProperty(user, fields.updateValue(user, issues.get(user, number()).issue.id, uuid("fieldId"), args["value"]))
            "list_relations" -> relations.list(user, number()).map(IssueRelationResponse::from)
            "add_relation" -> {
                val result = relations.add(user, number(), RelationKind.valueOf(text("kind")), number("target"))
                announceRelation(user, number(), number("target"))
                result.map(IssueRelationResponse::from)
            }
            "remove_relation" -> {
                val before = relations.list(user, number()).firstOrNull { it.id == uuid("relationId") }
                relations.remove(user, number(), uuid("relationId"))
                announceRelation(user, number(), before?.issue?.number)
                mapOf("removed" to true)
            }
            else -> error("Unregistered tool")
        }
    }

    private suspend fun detail(
        user: AuthenticatedUser,
        number: Int,
    ): Map<String, Any?> {
        val detail = issues.get(user, number)
        return mapOf(
            "issue" to IssueResponse.from(detail),
            "markdown" to markdown.render(detail.issue.content, detail.issue.description),
            "properties" to properties.list(user, CustomPropertyOwnerKind.ISSUE, detail.issue.id),
            "relations" to relations.list(user, number).map(IssueRelationResponse::from),
            "activity" to comments.activity(user, number),
            "blockComments" to threads.threads(user, ContentTarget(ContentTargetType.ISSUE, detail.issue.id)).threads,
            "files" to files.list(user, number),
        )
    }

    private suspend fun announce(
        user: AuthenticatedUser,
        change: IssueChange,
    ) {
        announcer.changed(user, change)
        change.issue.parent?.let { announcer.touched(user, it.number) }
        // An MCP call has no detail dialog to close later.
        announcer.left(user, change.issue.number)
    }

    private suspend fun announceProperty(
        user: AuthenticatedUser,
        change: CustomPropertyChange,
    ): Any {
        change.issueChange?.let { announce(user, it) }
        change.topics.forEach { hub.publish(it, user.id) }
        return mapOf("item" to change.item)
    }

    private suspend fun announceRelation(
        user: AuthenticatedUser,
        first: Int,
        second: Int?,
    ) {
        hub.publish(RealtimeHub.ISSUES, user.id)
        for (number in listOfNotNull(first, second).distinct()) {
            announcer.touched(user, number)
            issues.projectIdOf(user, number)?.let { hub.publish("project:$it", user.id) }
        }
    }

    private suspend fun options(
        user: AuthenticatedUser,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val projectId = args["projectId"] as? String
        val access = projectId?.let { projects.access(user, it) }
        return mapOf(
            "projectId" to access?.project?.id,
            "myRole" to access?.role,
            "canCreate" to (access?.role?.canEdit ?: true),
            "triageEnabled" to (access?.project?.triageEnabled ?: false),
            "willEnterTriage" to (access?.project?.triageEnabled == true && !access.role.canManage),
            "estimatesEnabled" to (access?.project?.estimatesEnabled ?: false),
            "estimateRange" to mapOf("min" to 0, "max" to 1000),
            "people" to people(user, args),
            "statuses" to statuses.all(),
            "labels" to labels.list().map(LabelResponse::from),
            "cycles" to projectId?.let { cycles.overview(user, it) },
            "projectFields" to projectId?.let { fields.list(user, it, false) }.orEmpty(),
            "priorities" to IssuePriority.entries.map { it.name },
            "customPropertyTypes" to CustomPropertyType.entries.map { it.name },
            "instructions" to "이름이 겹치면 사용자를 구분하세요. 공통 속성은 fieldId와 형식·multiple·defaultValue를 확인하세요. 기본값은 서버가 적용합니다. 생성 결과의 triage가 true이면 요청함에서 수락 대기 중입니다.",
        )
    }

    private suspend fun people(
        user: AuthenticatedUser,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val projectId = args["projectId"] as? String
        val items =
            if (projectId == null) {
                users.activeUsers().filter { matches("${it.loginId} ${it.displayName.orEmpty()}", args) }.map {
                    mapOf(
                        "person" to UserSummaryResponse.from(it),
                        "canAssign" to true,
                        "canSelectPerson" to true,
                    )
                }
            } else {
                projects.members(user, projectId).filter { matches("${it.loginId} ${it.displayName.orEmpty()}", args) }.map {
                    mapOf(
                        "person" to
                            mapOf("id" to it.userId, "loginId" to it.loginId, "displayName" to it.displayName, "avatarUrl" to ImageStorage.urlOf(it.avatarKey)),
                        "role" to it.role,
                        "canAssign" to true,
                        "canSelectPerson" to true,
                    )
                }
            }
        return page(items, args)
    }

    private fun body(fields: Map<String, Any?>): Map<String, Any?> {
        if (!fields.containsKey("markdown")) return fields
        if (fields.containsKey("content") ||
            fields.containsKey("description")
        ) {
            throw ValidationException("markdown", "CONFLICT", "markdown은 content·description과 함께 지정할 수 없습니다")
        }
        return fields.filterKeys { it != "markdown" } + ("content" to (fields["markdown"] as? String)?.let(markdown::document))
    }

    private fun matches(
        value: String,
        args: Map<String, Any?>,
    ) = value.contains((args["q"] as? String).orEmpty().trim(), ignoreCase = true)

    private fun limit(args: Map<String, Any?>) =
        ((args["limit"] as? Number)?.toInt() ?: 50).also {
            if (it !in
                1..100
            ) {
                throw ValidationException("limit", "INVALID", "1~100으로 지정하세요")
            }
        }

    private fun page(
        items: List<*>,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val limit = limit(args)
        val offset =
            ((args["offset"] as? Number)?.toInt() ?: 0).also {
                if (it !in
                    0..10_000
                ) {
                    throw ValidationException("offset", "INVALID", "0~10000으로 지정하세요")
                }
            }
        return mapOf("items" to items.drop(offset).take(limit), "total" to items.size, "nextOffset" to (offset + limit).takeIf { it < items.size })
    }
}
