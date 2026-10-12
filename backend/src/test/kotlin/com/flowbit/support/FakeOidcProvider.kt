package com.flowbit.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.Date

/**
 * A minimal OpenID Connect provider on a local port: discovery, keys and a token endpoint that
 * signs an ID token for whoever [claims] describes. Time in the token is real time, because the
 * token's expiry is checked against the system clock.
 */
class FakeOidcProvider : AutoCloseable {
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)

    val issuer: String = "http://localhost:${server.address.port}"

    /** Claims of the person "signing in"; `sub` is required. */
    var claims: Map<String, Any> = emptyMap()

    /** The nonce the next ID token carries: the one from the authorization request, unless a test wants otherwise. */
    var nonce: String = ""
    var audience: String = "flowbit"

    /** The form the application last posted to the token endpoint. */
    var lastTokenRequest: Map<String, String> = emptyMap()
    var lastTokenAuthorization: String? = null
    var lastTokenBody: String = ""

    /** What the in-house code exchange (/custom/token) answers. */
    var customProfile: String = "{}"

    init {
        server.createContext("/.well-known/openid-configuration") {
            it.json(
                """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks"}""",
            )
        }
        server.createContext("/jwks") { it.json(JWKSet(key.toPublicJWK()).toString()) }
        server.createContext("/token") {
            lastTokenAuthorization = it.requestHeaders.getFirst("Authorization")
            lastTokenRequest =
                String(it.requestBody.readAllBytes())
                    .split("&")
                    .filter { pair -> pair.contains("=") }
                    .associate { pair -> pair.substringBefore("=") to URLDecoder.decode(pair.substringAfter("="), Charsets.UTF_8) }
            it.json("""{"token_type":"Bearer","access_token":"test-access-token","id_token":"${idToken()}"}""")
        }
        // An in-house provider: the code exchange takes JSON and an API key, and answers with the profile.
        server.createContext("/custom/token") {
            lastTokenAuthorization = it.requestHeaders.getFirst("Authorization")
            lastTokenBody = String(it.requestBody.readAllBytes())
            it.json(customProfile)
        }
        server.start()
    }

    private fun idToken(): String {
        val now = Instant.now()
        val builder =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
        claims.forEach { (name, value) -> builder.claim(name, value) }
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), builder.build())
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private fun HttpExchange.json(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }
}
