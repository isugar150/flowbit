package com.flowbit.common.web

import org.slf4j.LoggerFactory
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

    init {
        // A value that is not an address (a typo in .env) quietly loses the links in notifications.
        val address = base
        if (address != null && !address.startsWith("http://") && !address.startsWith("https://")) {
            LoggerFactory.getLogger(javaClass).warn("FLOWBIT_PUBLIC_URL is not an http(s) address, so notifications will carry no link: {}", address)
        }
    }

    /** The full address of [path], or the path itself when the address of this installation is unknown. */
    fun of(path: String): String = base?.let { it + path } ?: path
}
