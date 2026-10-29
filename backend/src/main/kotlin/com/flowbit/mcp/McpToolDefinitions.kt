package com.flowbit.mcp

import com.flowbit.common.error.ValidationException
import com.flowbit.customproperty.application.CustomPropertyType
import com.flowbit.issue.application.RelationKind
import com.flowbit.issue.domain.IssuePriority
import com.flowbit.issue.domain.IssueStatus

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val annotations: Map<String, Boolean>,
)

/** Shared schemas for MCP discovery and the JSON integration API. */
object McpToolDefinitions {
    private fun text(
        description: String = "",
        nullable: Boolean = false,
    ): Map<String, Any?> =
        mapOf(
            "type" to if (nullable) listOf("string", "null") else "string",
            "description" to description,
        )

    private fun number() = mapOf("type" to "integer")

    private fun bool() = mapOf("type" to "boolean")

    private fun values(items: List<String>) = mapOf("type" to "string", "enum" to items)

    private fun array(items: Map<String, Any?>) = mapOf("type" to "array", "items" to items)

    private fun obj(
        properties: Map<String, Any?>,
        required: List<String> = emptyList(),
    ) = mapOf(
        "type" to "object",
        "properties" to properties,
        "required" to required,
        "additionalProperties" to false,
    )

    private val issue = text("티켓 키(FB-123) 또는 번호(123).")
    private val id = text("조회 도구가 반환한 UUID. 이름을 추정하지 마세요.")
    private val anyValue = mapOf<String, Any?>("description" to "속성 형식에 맞는 값. 비우려면 null.")
    private val content = mapOf("type" to listOf("object", "null"), "description" to "원본 편집기 JSON. 이미지·표·블록을 유지하려면 사용. markdown과 동시 지정 불가.")
    private val property =
        obj(mapOf("label" to text(), "type" to values(CustomPropertyType.entries.map { it.name }), "value" to anyValue), listOf("label", "type"))
    private val relation = obj(mapOf("kind" to values(RelationKind.entries.map { it.name }), "targetNumber" to number()), listOf("kind", "targetNumber"))
    private val fields =
        linkedMapOf<String, Any?>(
            "title" to text(),
            "description" to text(nullable = true),
            "content" to content,
            "markdown" to text("본문 교체. 제목·문단·굵게·기울임·링크·목록·인용·코드 지원. 복잡한 기존 문서는 content를 사용하세요.", true),
            "projectId" to text(nullable = true),
            "parentId" to text(nullable = true),
            "statusId" to id,
            "status" to values(IssueStatus.entries.map { it.name }),
            "priority" to values(IssuePriority.entries.map { it.name }),
            "assigneeIds" to array(id),
            "labelIds" to array(id),
            "startDate" to text("YYYY-MM-DD", true),
            "dueDate" to text("YYYY-MM-DD", true),
            "estimate" to mapOf("type" to listOf("integer", "null"), "minimum" to 0, "maximum" to 1000),
            "triage" to bool(),
        )
    private val paging = mapOf("q" to text("이름 또는 제목 검색"), "offset" to number(), "limit" to number())

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, Any?> = emptyMap(),
        required: List<String> = emptyList(),
        write: Boolean = false,
        destructive: Boolean = false,
    ) = McpTool(
        name,
        description,
        obj(properties, required),
        mapOf(
            "readOnlyHint" to !write,
            "destructiveHint" to (destructive || name.startsWith("update_") || name == "set_project_field"),
            "openWorldHint" to false,
        ),
    )

    val all =
        listOf(
            tool("list_issue_files", "티켓 본문·댓글·첨부·연결 문서의 사진과 파일 목록. 받은 fileId로 실제 내용 조회 또는 원본 다운로드.", mapOf("issue" to issue), listOf("issue")),
            tool(
                "read_file",
                "사진은 실제 image 응답, 텍스트·Office는 내용 구간, PDF는 page(기본 1)의 글. nextOffset/nextPage로 이어 읽으세요. BASE64는 원본 바이트 구간. 미지원/암호화/추출 제한은 downloadUrl 원본을 분석하세요.",
                mapOf(
                    "issue" to issue,
                    "fileId" to text(),
                    "mode" to values(listOf("AUTO", "TEXT", "BASE64")),
                    "offset" to number(),
                    "limit" to number(),
                    "page" to number(),
                ),
                listOf("issue", "fileId"),
            ),
            tool(
                "render_file_page",
                "PDF의 지정 페이지를 실제 이미지로 읽습니다. 스캔·그림·표 분석에 사용. page는 1부터.",
                mapOf("issue" to issue, "fileId" to text(), "page" to number()),
                listOf("issue", "fileId"),
            ),
            tool("get_document", "권한 있는 연결 문서의 원본 편집기 JSON·Markdown·블록 댓글 조회.", mapOf("documentId" to id), listOf("documentId")),
            tool(
                "attach_file",
                "파일 첨부. 128KB 이하 표준 Base64 data만 사용. 큰 파일은 list_issue_files의 uploadUrl에 Bearer 인증 multipart file 전송. placement ATTACHMENT(기본), BODY(본문 끝), COMMENT(새 댓글). image=true는 본문/댓글 사진. markdown은 새 댓글 글.",
                mapOf(
                    "issue" to issue,
                    "fileName" to text(),
                    "data" to text(),
                    "placement" to values(McpFilePlacement.entries.map { it.name }),
                    "image" to bool(),
                    "markdown" to text(),
                ),
                listOf("issue", "fileName", "data"),
                true,
            ),
            tool("list_projects", "접근 가능한 프로젝트와 내 역할 조회. 생성 전에 프로젝트 ID를 찾으세요.", paging),
            tool(
                "get_issue_options",
                "티켓 생성·수정에 필요한 상태·라벨·선택 가능한 사람·사이클·공통 속성 정의와 기본값. 프로젝트 생략 시 프로젝트 없는 티켓 기준. 사람은 페이지로 반환.",
                paging + mapOf("projectId" to id),
            ),
            tool("list_people", "프로젝트 사람 검색. 담당자 canAssign, 사람 속성 canSelectPerson 값을 확인하세요. 동명이인은 구분하세요.", paging + mapOf("projectId" to id)),
            tool("list_statuses", "워크스페이스의 실제 상태 ID·이름·범주·순서."),
            tool("list_issues", "화면과 같은 일반 티켓 목록. 요청함 대기·거절은 일반 목록에서 제외됩니다.", paging + mapOf("projectId" to id, "deleted" to bool())),
            tool("search_issues", "화면과 같은 검색으로 권한 있는 티켓 검색. 요청함 티켓도 찾을 수 있습니다.", mapOf("q" to text(), "limit" to number()), listOf("q")),
            tool(
                "get_issue",
                "티켓 상세·원본 content·Markdown·댓글·블록 댓글·속성·연결·파일 목록 조회. 사진·파일 내용도 요청받았다면 read_file·render_file_page로 실제 내용을 읽으세요. 수정 전 현재 값을 읽으세요.",
                mapOf(
                    "issue" to issue,
                ),
                listOf("issue"),
            ),
            tool(
                "create_issue",
                "티켓 생성. get_issue_options로 선택값 확인. 요청함이 켜진 프로젝트의 편집 역할은 자동 대기. 반환된 triage로 결과를 안내하세요.",
                mapOf(
                    "fields" to
                        obj(
                            fields +
                                mapOf(
                                    "customProperties" to array(property),
                                    "relations" to array(relation),
                                    "projectFields" to array(obj(mapOf("fieldId" to id, "value" to anyValue), listOf("fieldId"))),
                                ),
                            listOf("title"),
                        ),
                ),
                listOf("fields"),
                true,
            ),
            tool(
                "update_issue",
                "티켓 수정. fields에서 생략한 필드는 유지. null은 비우기. 공통 속성은 set_project_field, 개별 속성은 해당 CRUD 도구 사용. 사이클은 cycleId로 선택.",
                mapOf(
                    "issue" to issue,
                    "fields" to obj(fields + mapOf("cycleId" to text(nullable = true), "triageRejected" to bool())),
                ),
                listOf("issue", "fields"),
                true,
            ),
            tool("delete_issue", "티켓을 복원 가능하게 삭제. 화면과 같은 권한 적용.", mapOf("issue" to issue), listOf("issue"), true, true),
            tool("restore_issue", "삭제한 티켓 복원.", mapOf("issue" to issue), listOf("issue"), true),
            tool("list_comments", "티켓 댓글과 활동·댓글 수정/삭제 권한 조회.", mapOf("issue" to issue), listOf("issue")),
            tool(
                "create_comment",
                "티켓에 댓글 또는 답글 작성. markdown 또는 content 중 하나 필요.",
                mapOf(
                    "issue" to issue,
                    "markdown" to text(),
                    "content" to content,
                    "parentId" to id,
                    "attachmentIds" to array(id),
                ),
                listOf("issue"),
                true,
            ),
            tool(
                "update_comment",
                "내 댓글 수정. 다른 사람의 댓글은 수정할 수 없습니다.",
                mapOf("commentId" to id, "markdown" to text(), "content" to content, "attachmentIds" to array(id)),
                listOf("commentId"),
                true,
            ),
            tool("delete_comment", "댓글 삭제. 작성자 또는 화면과 같은 관리자 권한 필요.", mapOf("commentId" to id), listOf("commentId"), true, true),
            tool("list_custom_properties", "티켓의 공통·개별 사용자 속성과 현재 값 조회.", mapOf("issue" to issue), listOf("issue")),
            tool(
                "create_custom_property",
                "티켓에 개별 속성 추가. 공통 속성의 값은 set_project_field 사용.",
                mapOf(
                    "issue" to issue,
                    "label" to text(),
                    "type" to values(CustomPropertyType.entries.map { it.name }),
                    "value" to anyValue,
                ),
                listOf("issue", "label", "type"),
                true,
            ),
            tool(
                "update_custom_property",
                "개별 속성의 이름 또는 값 수정. 형식은 바꿀 수 없습니다.",
                mapOf(
                    "propertyId" to id,
                    "fields" to obj(mapOf("label" to text(), "value" to anyValue)),
                ),
                listOf("propertyId", "fields"),
                true,
            ),
            tool("delete_custom_property", "개별 속성 삭제. 프로젝트 공통 정의는 여기서 삭제하지 않습니다.", mapOf("propertyId" to id), listOf("propertyId"), true, true),
            tool(
                "set_project_field",
                "티켓의 프로젝트 공통 속성 값 변경. fieldId는 get_issue_options에서 조회. null은 값 비우기.",
                mapOf(
                    "issue" to issue,
                    "fieldId" to id,
                    "value" to anyValue,
                ),
                listOf("issue", "fieldId", "value"),
                true,
            ),
            tool("list_relations", "연결된 티켓 조회.", mapOf("issue" to issue), listOf("issue")),
            tool(
                "add_relation",
                "티켓 연결. BLOCKED_BY는 먼저 처리할 티켓, BLOCKS는 이후 처리할 티켓, RELATED는 함께 참고할 티켓.",
                mapOf(
                    "issue" to issue,
                    "target" to issue,
                    "kind" to values(RelationKind.entries.map { it.name }),
                ),
                listOf("issue", "target", "kind"),
                true,
            ),
            tool("remove_relation", "티켓 연결 해제.", mapOf("issue" to issue, "relationId" to id), listOf("issue", "relationId"), true, true),
        )

    fun validate(
        schema: Map<*, *>,
        value: Any?,
        path: String = "arguments",
    ) {
        val types =
            when (val type = schema["type"]) {
                is String -> listOf(type)
                is List<*> -> type
                else -> return
            }
        val actual =
            when (value) {
                null -> "null"
                is String -> "string"
                is Boolean -> "boolean"
                is Map<*, *> -> "object"
                is List<*> -> "array"
                is Number ->
                    if (value.toDouble() %
                        1.0 ==
                        0.0
                    ) {
                        "integer"
                    } else {
                        "number"
                    }
                else -> "unknown"
            }
        if (actual !in types) throw ValidationException(path, "INVALID", "값의 형식이 올바르지 않습니다")
        if (schema["enum"] is List<*> && value !in schema["enum"] as List<*>) throw ValidationException(path, "INVALID", "허용된 값을 선택하세요")
        if (value is Map<*, *>) {
            val properties = schema["properties"] as? Map<*, *> ?: return
            (schema["required"] as? List<*>).orEmpty().forEach { if (!value.containsKey(it)) throw ValidationException("$path.$it", "REQUIRED", "필수 값입니다") }
            value.forEach { (key, item) ->
                val child = properties[key] as? Map<*, *>
                if (child == null && schema["additionalProperties"] == false) throw ValidationException("$path.$key", "UNKNOWN", "지원하지 않는 필드입니다")
                if (child != null) validate(child, item, "$path.$key")
            }
        }
        if (value is List<*>) {
            (schema["items"] as? Map<*, *>)?.let { itemSchema ->
                value.forEachIndexed { index, item -> validate(itemSchema, item, "$path[$index]") }
            }
        }
    }
}
