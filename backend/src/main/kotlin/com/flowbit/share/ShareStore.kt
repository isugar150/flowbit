package com.flowbit.share

import com.flowbit.common.UuidV7
import com.flowbit.common.web.ClientInfo
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

/** One time a link was opened. */
data class ShareAccess(
    val accessedAt: Instant,
    val ipAddress: String?,
    val userAgent: String?,
    val embedded: Boolean,
    val referrer: String?,
    /** What was opened at the link when it has more than one page (`issue:13`, `document:<id>`); null for the link's own page. */
    val item: String? = null,
)

/** Rows of `share_links` and `share_accesses` (docs/features/sharing.md). */
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

    suspend fun recordAccess(
        shareId: UUID,
        at: Instant,
        client: ClientInfo,
        embedded: Boolean,
        referrer: String?,
        item: String? = null,
    ) {
        databaseClient
            .sql(
                """
                insert into share_accesses (id, share_id, accessed_at, ip_address, user_agent, embedded, referrer, item)
                values (:id, :shareId, :accessedAt, :ipAddress, :userAgent, :embedded, :referrer, :item)
                """.trimIndent(),
            ).bind("id", UuidV7.generate(at))
            .bind("shareId", shareId)
            .bind("accessedAt", at(at))
            .bind("embedded", embedded)
            .bindText("ipAddress", client.ipAddress)
            .bindText("userAgent", client.userAgent)
            .bindText("referrer", referrer)
            .bindText("item", item)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** The latest times the link was opened, newest first. */
    suspend fun recentAccesses(
        shareId: UUID,
        limit: Int,
    ): List<ShareAccess> =
        databaseClient
            .sql(
                """
                select accessed_at, ip_address, user_agent, embedded, referrer, item
                from share_accesses where share_id = :shareId order by accessed_at desc, id desc limit :limit
                """.trimIndent(),
            ).bind("shareId", shareId)
            .bind("limit", limit)
            .map { row ->
                ShareAccess(
                    accessedAt = checkNotNull(row.get("accessed_at", OffsetDateTime::class.java)).toInstant(),
                    ipAddress = row.get("ip_address", String::class.java),
                    userAgent = row.get("user_agent", String::class.java),
                    embedded = checkNotNull(row.get("embedded", Boolean::class.javaObjectType)),
                    referrer = row.get("referrer", String::class.java),
                    item = row.get("item", String::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun countAccesses(shareId: UUID): Long =
        databaseClient
            .sql("select count(*) as n from share_accesses where share_id = :shareId")
            .bind("shareId", shareId)
            .map { row -> checkNotNull(row.get("n", Long::class.javaObjectType)) }
            .one()
            .awaitSingle()

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
