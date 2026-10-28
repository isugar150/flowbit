package com.flowbit.savedview

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.user.domain.UserRole
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class SavedViewOwner(
    val id: UUID,
    val loginId: String,
    val displayName: String?,
)

data class SavedViewResponse(
    val id: UUID,
    val name: String,
    /** The list it belongs to: `/issues`, `/my-issues` or `/my-issues/created`. */
    val path: String,
    /** The list's query string without the `?`. */
    val query: String,
    val shared: Boolean,
    val owner: SavedViewOwner,
    /** Whether the caller made it (and so may change it). */
    val mine: Boolean,
    /** Whether the caller may delete it: the maker, or an administrator. */
    val canDelete: Boolean,
)

data class SaveViewRequest(
    val name: String,
    val path: String,
    val query: String = "",
    val shared: Boolean = false,
)

/** What is present changes: a new name, the query of the list as it is now, or who may use the view. */
data class UpdateViewRequest(
    val name: String? = null,
    val query: String? = null,
    val shared: Boolean? = null,
)

/**
 * Views of the issue lists a person saved (docs/features/saved-views.md): their own, and the ones other people shared with
 * everyone. A view is a list address (`path` and `query`), so applying one just opens that address. Only the maker changes a view;
 * an administrator may also delete one.
 */
@Service
class SavedViewService(
    private val databaseClient: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun list(user: AuthenticatedUser): List<SavedViewResponse> =
        databaseClient
            .sql("$SELECT where v.owner_id = :userId or v.shared order by v.shared, lower(v.name), v.created_at")
            .bind("userId", user.id)
            .map { row, _ -> view(row, user) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun create(
        user: AuthenticatedUser,
        request: SaveViewRequest,
    ): SavedViewResponse {
        val name = checkedName(request.name)
        val path = checkedPath(request.path)
        val query = checkedQuery(request.query)
        val count =
            databaseClient
                .sql("select count(*) from saved_views where owner_id = :userId")
                .bind("userId", user.id)
                .map { row, _ -> (row.get(0) as Number).toInt() }
                .one()
                .awaitSingle()
        if (count >= MAX_PER_PERSON) throw ConflictException("TOO_MANY_VIEWS", "저장한 뷰는 ${MAX_PER_PERSON}개까지 만들 수 있습니다")
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val id = UuidV7.generate(clock.instant())
        databaseClient
            .sql(
                "insert into saved_views (id, owner_id, name, path, query, shared, created_at, updated_at) " +
                    "values (:id, :userId, :name, :path, :query, :shared, :now, :now)",
            ).bind("id", id)
            .bind("userId", user.id)
            .bind("name", name)
            .bind("path", path)
            .bind("query", query)
            .bind("shared", request.shared)
            .bind("now", now)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return find(user, id)
    }

    suspend fun update(
        user: AuthenticatedUser,
        id: UUID,
        request: UpdateViewRequest,
    ): SavedViewResponse {
        val current = find(user, id)
        if (!current.mine) throw notFound()
        val name = request.name?.let(::checkedName) ?: current.name
        val query = request.query?.let(::checkedQuery) ?: current.query
        val shared = request.shared ?: current.shared
        databaseClient
            .sql("update saved_views set name = :name, query = :query, shared = :shared, updated_at = :now where id = :id")
            .bind("name", name)
            .bind("query", query)
            .bind("shared", shared)
            .bind("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return find(user, id)
    }

    suspend fun delete(
        user: AuthenticatedUser,
        id: UUID,
    ) {
        val current = find(user, id)
        if (!current.mine && user.role != UserRole.ADMIN) throw notFound()
        databaseClient
            .sql("delete from saved_views where id = :id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** A view the caller may see: theirs, or one that is shared. */
    private suspend fun find(
        user: AuthenticatedUser,
        id: UUID,
    ): SavedViewResponse =
        databaseClient
            .sql("$SELECT where v.id = :id and (v.owner_id = :userId or v.shared)")
            .bind("id", id)
            .bind("userId", user.id)
            .map { row, _ -> view(row, user) }
            .all()
            .collectList()
            .awaitSingle()
            .firstOrNull() ?: throw notFound()

    private fun view(
        row: io.r2dbc.spi.Readable,
        caller: AuthenticatedUser,
    ): SavedViewResponse {
        val ownerId = checkNotNull(row.get("owner_id", UUID::class.java))
        return SavedViewResponse(
            id = checkNotNull(row.get("id", UUID::class.java)),
            name = checkNotNull(row.get("name", String::class.java)),
            path = checkNotNull(row.get("path", String::class.java)),
            query = checkNotNull(row.get("query", String::class.java)),
            shared = checkNotNull(row.get("shared", Boolean::class.javaObjectType)),
            owner = SavedViewOwner(ownerId, checkNotNull(row.get("login_id", String::class.java)), row.get("display_name", String::class.java)),
            mine = ownerId == caller.id,
            canDelete = ownerId == caller.id || caller.role == UserRole.ADMIN,
        )
    }

    private fun checkedName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name.length > NAME_MAX_LENGTH) {
            throw ValidationException("name", "INVALID", "뷰 이름은 1자 이상 ${NAME_MAX_LENGTH}자 이하여야 합니다")
        }
        return name
    }

    private fun checkedPath(path: String): String {
        if (path !in PATHS) throw ValidationException("path", "INVALID", "값이 올바르지 않습니다")
        return path
    }

    private fun checkedQuery(raw: String): String {
        val query = raw.trim().removePrefix("?")
        if (query.length > QUERY_MAX_LENGTH) throw ValidationException("query", "TOO_LONG", "뷰의 조건이 너무 깁니다")
        return query
    }

    private fun notFound() = NotFoundException("VIEW_NOT_FOUND", "뷰를 찾을 수 없습니다")

    companion object {
        const val NAME_MAX_LENGTH = 40
        const val QUERY_MAX_LENGTH = 1000
        const val MAX_PER_PERSON = 100
        val PATHS = setOf("/issues", "/my-issues", "/my-issues/created")
        private const val SELECT =
            "select v.id, v.owner_id, v.name, v.path, v.query, v.shared, u.login_id, u.display_name " +
                "from saved_views v join users u on u.id = v.owner_id"
    }
}

@RestController
@RequestMapping("/api/v1/views")
class SavedViewController(
    private val service: SavedViewService,
) {
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): List<SavedViewResponse> = service.list(user)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestBody body: SaveViewRequest,
    ): SavedViewResponse = service.create(user, body)

    @PatchMapping("/{id}")
    suspend fun update(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @RequestBody body: UpdateViewRequest,
    ): SavedViewResponse = service.update(user, id, body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ) = service.delete(user, id)
}
