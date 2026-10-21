package com.flowbit.content

import com.flowbit.auth.application.AuthenticatedUser
import org.springframework.stereotype.Service

/** Reads a page's threads, activity and comments for the person looking. */
@Service
class ContentFeedService(
    private val threads: ContentThreadService,
    private val log: ContentLog,
    private val comments: ContentCommentService,
    private val store: ContentCommentStore,
    private val subscriptions: ContentSubscriptionService,
) {
    suspend fun feed(
        user: AuthenticatedUser,
        target: ContentTarget,
    ): ContentFeed {
        val found = threads.threads(user, target)
        if (!target.type.hasActivity) return ContentFeed(found.context, found.threads, emptyList(), emptyList())
        val emoji = store.activityReactions(target, user.id)
        val items = log.list(target).map { it.copy(reactions = emoji[it.id].orEmpty()) }
        return ContentFeed(found.context, found.threads, items, comments.list(user, found.context), subscriptions.of(user, found.context))
    }
}
