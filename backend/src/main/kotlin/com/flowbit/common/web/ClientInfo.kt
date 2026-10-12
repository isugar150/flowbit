package com.flowbit.common.web

import org.springframework.web.server.ServerWebExchange

/** Who made the request, as far as the server can tell. Used for rate limiting and audit records. */
data class ClientInfo(
    val ipAddress: String?,
    val userAgent: String?,
) {
    companion object {
        private const val MAX_USER_AGENT_LENGTH = 512

        fun from(exchange: ServerWebExchange): ClientInfo =
            ClientInfo(
                ipAddress =
                    exchange.request.remoteAddress
                        ?.address
                        ?.hostAddress,
                userAgent =
                    exchange.request.headers
                        .getFirst("User-Agent")
                        ?.take(MAX_USER_AGENT_LENGTH),
            )
    }
}
