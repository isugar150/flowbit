package com.flowbit.support

import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class ApiResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    @Suppress("UNCHECKED_CAST")
    fun json(): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * Talks to the running application over real HTTP the way a browser would:
 * keeps cookies between calls and sends the CSRF header on state-changing requests.
 */
class ApiClient(
    private val baseUrl: String,
) {
    private val http = HttpClient.newHttpClient()
    val cookies = mutableMapOf<String, String>()

    /** Set to false to act like a cross-site request that cannot read the CSRF cookie. */
    var sendCsrfHeader = true

    val sessionCookie: String?
        get() = cookies[SESSION_COOKIE]

    fun get(path: String): ApiResponse = send("GET", path, null)

    fun post(
        path: String,
        json: String? = null,
    ): ApiResponse {
        if (sendCsrfHeader && CSRF_COOKIE !in cookies) {
            get("/api/v1/auth/csrf")
        }
        return send("POST", path, json)
    }

    fun setup(
        loginId: String,
        password: String,
    ): ApiResponse = post("/api/v1/setup", credentials(loginId, password))

    fun login(
        loginId: String,
        password: String,
    ): ApiResponse = post("/api/v1/auth/login", credentials(loginId, password))

    fun logout(): ApiResponse = post("/api/v1/auth/logout")

    fun me(): ApiResponse = get("/api/v1/me")

    private fun credentials(
        loginId: String,
        password: String,
    ): String = """{"loginId":"$loginId","password":"$password"}"""

    private fun send(
        method: String,
        path: String,
        json: String?,
    ): ApiResponse {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        if (method != "GET" && sendCsrfHeader) {
            cookies[CSRF_COOKIE]?.let { builder.header("X-XSRF-TOKEN", it) }
        }
        val publisher =
            if (json == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                builder.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(json)
            }
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("Set-Cookie").forEach(::storeCookie)
        return ApiResponse(response.statusCode(), response.body(), response.headers().map())
    }

    private fun storeCookie(header: String) {
        val parts = header.split(";").map { it.trim() }
        val name = parts.first().substringBefore("=")
        val value = parts.first().substringAfter("=")
        val expired = value.isEmpty() || parts.any { it.equals("Max-Age=0", ignoreCase = true) }
        if (expired) cookies.remove(name) else cookies[name] = value
    }

    companion object {
        const val SESSION_COOKIE = "FLOWBIT_SESSION"
        const val CSRF_COOKIE = "XSRF-TOKEN"
    }
}
