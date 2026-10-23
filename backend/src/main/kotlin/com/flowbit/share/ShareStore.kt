package com.flowbit.share

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One person's share of one kind of page with the parameters that pick what of it is shown. `target` is those parameters in their stored form. */
data class ShareLink(
    val id: UUID,
    val token: String,
    val ownerId: UUID,
    val kind: String,
    val target: String,
    val createdAt: Instant,
)

/** A link that is in use, with the name of the person who shared it (null when they have none) for the page it opens. */
data class OpenShare(
    val link: ShareLink,
    val ownerName: String?,
)

/** Rows of `share_links` (docs/features/sharing.md). */
@Component
class ShareStore(
    private val databaseClient: DatabaseClient,
) {
    /** The link in use for this person, kind and target, if there is one. */
    suspend fun findLive(
        ownerId: UUID,
        kind: String,
        target: String,
    ): ShareLink? =
        databaseClient
            .sql("select $LINK_COLUMNS from share_links l where l.owner_id = :ownerId and l.kind = :kind and l.target = :target and l.revoked_at is null")
            .bind("ownerId", ownerId)
            .bind("kind", kind)
            .bind("target", target)
            .map { row -> linkOf(row) }
            .one()
            .awaitSingleOrNull()

    /**
     * Adds the link unless this person already has one in use for the same kind and target (two
     * requests at once make one link). Whether this one was added.
     */
    suspend fun insertIfAbsent(link: ShareLink): Boolean =
        databaseClient
            .sql(
                """
                insert into share_links (id, token, owner_id, kind, target, created_at)
                values (:id, :token, :ownerId, :kind, :target, :createdAt)
                on conflict (owner_id, kind, target) where revoked_at is null do nothing
                """.trimIndent(),
            ).bind("id", link.id)
            .bind("token", link.token)
            .bind("ownerId", link.ownerId)
            .bind("kind", link.kind)
            .bind("target", link.target)
            .bind("createdAt", at(link.createdAt))
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    /**
     * The links in use of this person, the newest first: of one kind when [kind] is given, [limit] of them from the
     * [offset]th. A page of the person's list of their links (D-204).
     */
    suspend fun listLive(
        ownerId: UUID,
        kind: String?,
        limit: Int,
        offset: Int,
    ): List<ShareLink> =
        databaseClient
            .sql(
                """
                select $LINK_COLUMNS
                from share_links l
                where l.owner_id = :ownerId and l.revoked_at is null and (cast(:kind as text) is null or l.kind = :kind)
                order by l.created_at desc, l.id desc
                limit :limit offset :offset
                """.trimIndent(),
            ).bind("ownerId", ownerId)
            .bindText("kind", kind)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row -> linkOf(row) }
            .all()
            .collectList()
            .awaitSingle()

    /** How many links in use the person has of each kind. */
    suspend fun countLiveByKind(ownerId: UUID): Map<String, Long> =
        databaseClient
            .sql("select kind, count(*) as n from share_links where owner_id = :ownerId and revoked_at is null group by kind")
            .bind("ownerId", ownerId)
            .map { row -> checkNotNull(row.get("kind", String::class.java)) to checkNotNull(row.get("n", Long::class.javaObjectType)) }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()

    suspend fun findById(id: UUID): ShareLink? =
        databaseClient
            .sql("select $LINK_COLUMNS from share_links l where l.id = :id")
            .bind("id", id)
            .map { row -> linkOf(row) }
            .one()
            .awaitSingleOrNull()

    /** Stops a link in use. Whether it was in use. */
    suspend fun revoke(
        id: UUID,
        now: Instant,
    ): Boolean =
        databaseClient
            .sql("update share_links set revoked_at = :now where id = :id and revoked_at is null")
            .bind("id", id)
            .bind("now", at(now))
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    /** The link with this token, if it is in use and the person who shared it can still sign in. */
    suspend fun findOpen(token: String): OpenShare? =
        databaseClient
            .sql(
                """
                select $LINK_COLUMNS, u.display_name as owner_name
                from share_links l join users u on u.id = l.owner_id
                where l.token = :token and l.revoked_at is null and u.status = 'ACTIVE'
                """.trimIndent(),
            ).bind("token", token)
            .map { row -> OpenShare(linkOf(row), row.get("owner_name", String::class.java)) }
            .one()
            .awaitSingleOrNull()

    private fun linkOf(row: Readable) =
        ShareLink(
            id = checkNotNull(row.get("id", UUID::class.java)),
            token = checkNotNull(row.get("token", String::class.java)),
            ownerId = checkNotNull(row.get("owner_id", UUID::class.java)),
            kind = checkNotNull(row.get("kind", String::class.java)),
            target = checkNotNull(row.get("target", String::class.java)),
            createdAt = checkNotNull(row.get("created_at", OffsetDateTime::class.java)).toInstant(),
        )

    private fun at(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun DatabaseClient.GenericExecuteSpec.bindText(
        name: String,
        value: String?,
    ) = if (value != null) bind(name, value) else bindNull(name, String::class.java)

    private companion object {
        const val LINK_COLUMNS = "l.id, l.token, l.owner_id, l.kind, l.target, l.created_at"
    }
}
