package com.flowbit.common.error

import com.flowbit.common.web.RequestIdFilter
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/** Builds the RFC 9457 problem document every error response uses (docs/06-api-conventions.md). */
object Problems {
    fun create(
        exchange: ServerWebExchange,
        status: HttpStatusCode,
        code: String,
        title: String,
        fieldErrors: List<FieldError> = emptyList(),
    ): ProblemDetail {
        val body = ProblemDetail.forStatus(status)
        body.type = URI.create("urn:flowbit:problem:" + code.lowercase().replace('_', '-'))
        body.title = title
        body.setProperty("code", code)
        RequestIdFilter.requestId(exchange)?.let { body.setProperty("traceId", it) }
        if (fieldErrors.isNotEmpty()) {
            body.setProperty("errors", fieldErrors)
        }
        return body
    }
}

/** Writes a problem document directly to the response, for failures raised before a controller runs. */
@Component
class ProblemResponseWriter(
    private val jsonMapper: JsonMapper,
) {
    fun write(
        exchange: ServerWebExchange,
        status: HttpStatusCode,
        code: String,
        title: String,
    ): Mono<Void> {
        val response = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        val bytes = jsonMapper.writeValueAsBytes(Problems.create(exchange, status, code, title))
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
    }
}
