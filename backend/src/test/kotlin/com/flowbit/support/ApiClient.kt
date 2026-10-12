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
    val bytes: ByteArray = body.toByteArray(),
) {
    @Suppress("UNCHECKED_CAST")
    fun json(): Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun jsonList(): List<Map<String, Any?>> = mapper.readValue(body, List::class.java) as List<Map<String, Any?>>

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

    fun get(path: String): ApiResponse = send("GET", path, null, null)

    fun post(
        path: String,
        json: String? = null,
    ): ApiResponse = change("POST", path, json?.toByteArray(), "application/json")

    fun patch(
        path: String,
        json: String,
    ): ApiResponse = change("PATCH", path, json.toByteArray(), "application/json")

    fun put(
        path: String,
        json: String,
    ): ApiResponse = change("PUT", path, json.toByteArray(), "application/json")

    fun delete(path: String): ApiResponse = change("DELETE", path, null, null)

    /** Sends one file as the `file` part of a multipart/form-data PUT, the way the browser form does. */
    fun putFile(
        path: String,
        fileName: String,
        contentType: String,
        content: ByteArray,
    ): ApiResponse = sendFile("PUT", path, fileName, contentType, content)

    fun postFile(
        path: String,
        fileName: String,
        contentType: String,
        content: ByteArray,
    ): ApiResponse = sendFile("POST", path, fileName, contentType, content)

    private fun sendFile(
        method: String,
        path: String,
        fileName: String,
        contentType: String,
        content: ByteArray,
    ): ApiResponse {
        val boundary = "----flowbit-test-boundary"
        val head =
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                "Content-Type: $contentType\r\n\r\n"
        val body = head.toByteArray() + content + "\r\n--$boundary--\r\n".toByteArray()
        return change(method, path, body, "multipart/form-data; boundary=$boundary")
    }

    private fun change(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
    ): ApiResponse {
        if (sendCsrfHeader && CSRF_COOKIE !in cookies) {
            get("/api/v1/auth/csrf")
        }
        return send(method, path, body, contentType)
    }

    fun setup(
        loginId: String,
        password: String,
        workspaceName: String? = null,
        workspaceKey: String? = null,
    ): ApiResponse =
        post(
            "/api/v1/setup",
            if (workspaceName == null) {
                credentials(loginId, password)
            } else {
                """{"loginId":"$loginId","password":"$password","workspaceName":"$workspaceName","workspaceKey":"${workspaceKey.orEmpty()}"}"""
            },
        )

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
        body: ByteArray?,
        contentType: String?,
    ): ApiResponse {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        if (method != "GET" && sendCsrfHeader) {
            cookies[CSRF_COOKIE]?.let { builder.header("X-XSRF-TOKEN", it) }
        }
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                contentType?.let { builder.header("Content-Type", it) }
                HttpRequest.BodyPublishers.ofByteArray(body)
            }
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray())
        response.headers().allValues("Set-Cookie").forEach(::storeCookie)
        return ApiResponse(response.statusCode(), String(response.body()), response.headers().map(), response.body())
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
