package com.flowbit.common.error

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

/** Turns failures raised by controllers into problem documents. Security failures are written by SecurityConfig. */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(
        ex: ApiException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        val headers = HttpHeaders()
        ex.retryAfter?.let { headers.set(HttpHeaders.RETRY_AFTER, it.seconds.coerceAtLeast(1).toString()) }
        return respond(exchange, ex.status, ex.code, ex.message ?: ex.code, ex.fieldErrors, headers)
    }

    @ExceptionHandler(Exception::class)
    fun handleOther(
        ex: Exception,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        if (ex is ErrorResponse) {
            val status = ex.statusCode
            val code = HttpStatus.resolve(status.value())?.name ?: "ERROR"
            return respond(exchange, status, code, titles[status.value()] ?: ex.body.title ?: code)
        }
        log.error("Unhandled exception (traceId={})", exchange.getAttribute<String>("flowbit.requestId"), ex)
        return respond(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버에서 오류가 발생했습니다")
    }

    private fun respond(
        exchange: ServerWebExchange,
        status: HttpStatusCode,
        code: String,
        title: String,
        fieldErrors: List<FieldError> = emptyList(),
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<ProblemDetail> {
        headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        return ResponseEntity.status(status).headers(headers).body(Problems.create(exchange, status, code, title, fieldErrors))
    }

    private companion object {
        val titles =
            mapOf(
                400 to "요청 형식이 올바르지 않습니다",
                404 to "요청한 대상을 찾을 수 없습니다",
                405 to "지원하지 않는 요청 방식입니다",
            )
    }
}
