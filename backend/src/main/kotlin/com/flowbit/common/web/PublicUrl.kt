package com.flowbit.common.web

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties("flowbit")
data class FlowbitProperties(
    /** The address people open Flowbit at, such as https://flowbit.example.com. May be empty. */
    val publicUrl: String = "",
)

/** Turns paths into addresses people outside the app (mail, other services) can open. */
@Component
class PublicUrl(
    properties: FlowbitProperties,
) {
    /** Without a trailing slash; null when no address was configured. */
    val base: String? =
        properties.publicUrl
            .trim()
            .trimEnd('/')
            .ifEmpty { null }

    /** The full address of [path], or the path itself when the address of this installation is unknown. */
    fun of(path: String): String = base?.let { it + path } ?: path
}
