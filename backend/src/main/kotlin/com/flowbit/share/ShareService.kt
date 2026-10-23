package com.flowbit.share

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.common.UuidV7
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.common.web.ClientInfo
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A link as its owner sees it: with what it shares as parameters. */
data class SharedLink(
    val link: ShareLink,
    val params: Map<String, String>,
)

/** A link in a person's list of their links: what it is, how often it was opened and when last. */
data class ShareSummary(
    val link: ShareLink,
    val params: Map<String, String>,
    val accessCount: Long,
    val lastAccessedAt: Instant?,
    /** What the link is about when its parameters do not say it (an issue: "FB-12 제목"); null otherwise. */
    val subject: String?,
)

/** A page of a person's list of their links, with how many there are in all and of each kind. */
data class ShareListPage(
    val total: Long,
    val kinds: Map<String, Long>,
    val items: List<ShareSummary>,
)

data class ShareAccessPage(
    val total: Long,
    val items: List<ShareAccess>,
)

/** What is shown at a link: who shared it, the parameters it was made with, and the kind's content. */
data class PublicShare(
    val kind: String,
    val sharedBy: String?,
    val params: Map<String, String>,
    val content: Any,
)

/**
 * Share links (docs/features/sharing.md, D-197): a person makes a link to a page, anyone who has
 * the address opens it without signing in, and every opening is recorded. What a link shows is
 * the kind's own ([ShareContentProvider]); this is the same for all of them.
 */
@Service
class ShareService(
    providers: List<ShareContentProvider>,
    private val store: ShareStore,
    private val auditService: AuditService,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    private val providers = providers.associateBy { it.kind.name }

    /**
     * The link for this person, kind and parameters: the one they already have, so that sharing the
     * same page again gives the same address, or a new one.
     */
    suspend fun share(
        user: AuthenticatedUser,
        kind: String?,
        params: Map<String, String>?,
        client: ClientInfo,
    ): SharedLink {
        val provider = providers[kind] ?: throw ValidationException("kind", "INVALID", "공유할 수 없는 종류입니다")
        val normalized = provider.normalize(params.orEmpty())
        provider.check(user, normalized)
        val target = encode(normalized)
        store.findLive(user.id, provider.kind.name, target)?.let { return SharedLink(it, normalized) }
        val now = clock.instant()
        val added = store.insertIfAbsent(ShareLink(UuidV7.generate(now), newToken(), user.id, provider.kind.name, target, now))
        val link = checkNotNull(store.findLive(user.id, provider.kind.name, target))
        if (added) {
            auditService.record(AuditEventType.SHARE_LINK_CREATED, user.id, client, mapOf("kind" to provider.kind.name, "target" to target))
        }
        return SharedLink(link, normalized)
    }

    /** Stops the person's own link: it opens no more. */
    suspend fun revoke(
        user: AuthenticatedUser,
        id: UUID,
        client: ClientInfo,
    ) {
        val link = ownLink(user, id)
        if (store.revoke(link.id, clock.instant())) {
            auditService.record(AuditEventType.SHARE_LINK_REVOKED, user.id, client, mapOf("kind" to link.kind, "target" to link.target))
        }
    }

    /**
     * A page of the links the person has made and has not stopped (their settings page lists them, D-202, D-204): of one kind
     * when `kind` is given, in the order of `sort`. `total` is how many there are of that kind; `kinds` how many of each there are.
     */
    suspend fun list(
        user: AuthenticatedUser,
        kind: String?,
        sort: ShareSort,
        limit: Int,
        offset: Int,
    ): ShareListPage {
        if (kind != null && kind !in providers) throw ValidationException("kind", "INVALID", "값이 올바르지 않습니다")
        val kinds = store.countLiveByKind(user.id)
        val items =
            store.listLive(user.id, kind, sort, limit.coerceIn(1, MAX_LIST), offset.coerceAtLeast(0)).map { stats ->
                val params = decode(stats.link.target)
                ShareSummary(stats.link, params, stats.accessCount, stats.lastAccessedAt, providers[stats.link.kind]?.describe(user.id, params))
            }
        return ShareListPage(total = if (kind == null) kinds.values.sum() else kinds[kind] ?: 0, kinds = kinds, items = items)
    }

    /** The latest times the person's own link was opened, [limit] of them at most. */
    suspend fun accesses(
        user: AuthenticatedUser,
        id: UUID,
        limit: Int = RECENT_ACCESSES,
    ): ShareAccessPage {
        val link = ownLink(user, id)
        return ShareAccessPage(store.countAccesses(link.id), store.recentAccesses(link.id, limit.coerceIn(1, MAX_ACCESSES)))
    }

    /**
     * Opens a link for someone who has only its address. A token that is not right, a link that was
     * stopped and a person who cannot sign in any more all answer the same: not found.
     * `embedded` and `referrer` are what the page itself says about where it is shown; they are for the record only.
     */
    suspend fun open(
        token: String,
        client: ClientInfo,
        embedded: Boolean,
        referrer: String?,
    ): PublicShare {
        rateLimiter.check("share-open", client.ipAddress, OPENS_PER_MINUTE)
        val opened = store.findOpen(token) ?: throw notFound()
        val provider = providers[opened.link.kind] ?: throw notFound()
        val params = decode(opened.link.target)
        val content = provider.content(opened.link.ownerId, params, token)
        store.recordAccess(opened.link.id, clock.instant(), client, embedded, referrerOf(referrer))
        return PublicShare(opened.link.kind, opened.ownerName?.takeIf { it.isNotBlank() }, params, content)
    }

    /**
     * A page the shared one leads to (a sub-issue or a document of a shared issue), opened like the link's own page and recorded
     * with what it was (`item`: `issue:13`). Not found when it is not part of what was shared.
     */
    suspend fun openItem(
        token: String,
        type: String,
        id: String,
        client: ClientInfo,
        embedded: Boolean,
        referrer: String?,
    ): PublicShare {
        rateLimiter.check("share-open", client.ipAddress, OPENS_PER_MINUTE)
        val opened = store.findOpen(token) ?: throw notFound()
        val provider = providers[opened.link.kind] ?: throw notFound()
        val params = decode(opened.link.target)
        val content = provider.item(opened.link.ownerId, params, token, type, id) ?: throw notFound()
        store.recordAccess(opened.link.id, clock.instant(), client, embedded, referrerOf(referrer), "$type:$id".take(MAX_ITEM_LENGTH))
        return PublicShare(opened.link.kind, opened.ownerName?.takeIf { it.isNotBlank() }, params, content)
    }

    /**
     * Checks that a picture or file may be served to whoever has the link: it must be one the shared pages hold. Not found
     * otherwise. Not recorded (a page holds many), and counted apart from the pages.
     */
    suspend fun checkFile(
        token: String,
        file: SharedFile,
        client: ClientInfo,
    ) {
        rateLimiter.check("share-file", client.ipAddress, FILES_PER_MINUTE)
        val opened = store.findOpen(token) ?: throw notFound()
        val provider = providers[opened.link.kind] ?: throw notFound()
        if (!provider.mayServe(opened.link.ownerId, decode(opened.link.target), file)) throw notFound()
    }

    private fun referrerOf(referrer: String?) = referrer?.trim()?.take(MAX_REFERRER_LENGTH)?.takeIf { it.isNotEmpty() }

    private suspend fun ownLink(
        user: AuthenticatedUser,
        id: UUID,
    ): ShareLink = store.findById(id)?.takeIf { it.ownerId == user.id } ?: throw notFound()

    private fun notFound() = NotFoundException("SHARE_NOT_FOUND", "이 링크는 열 수 없습니다")

    internal companion object {
        const val RECENT_ACCESSES = 20
        const val MAX_ACCESSES = 200
        const val MAX_LIST = 100
        const val OPENS_PER_MINUTE = 120
        const val FILES_PER_MINUTE = 600
        private const val MAX_REFERRER_LENGTH = 512
        private const val MAX_ITEM_LENGTH = 100
        private val random = SecureRandom()

        /** 256 random bits as 43 characters of an address. */
        fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

        /** The parameters by name, `k=v&k=v`, so that the same parameters are always the same text. */
        fun encode(params: Map<String, String>): String =
            params.entries
                .sortedBy { it.key }
                .joinToString("&") { "${URLEncoder.encode(it.key, StandardCharsets.UTF_8)}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }

        fun decode(target: String): Map<String, String> =
            target
                .split("&")
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val (key, value) = pair.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
                }
    }
}
