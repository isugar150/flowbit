package com.flowbit.common.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

/** Gives every request an id that is returned in `X-Request-Id` and in error responses as `traceId`. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val requestId = UUID.randomUUID().toString()
        exchange.attributes[ATTRIBUTE] = requestId
        exchange.response.headers.set("X-Request-Id", requestId)
        return chain.filter(exchange)
    }

    companion object {
        const val ATTRIBUTE = "flowbit.requestId"

        fun requestId(exchange: ServerWebExchange): String? = exchange.getAttribute(ATTRIBUTE)
    }
}
