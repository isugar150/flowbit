package com.flowbit.common.error

import org.springframework.http.HttpStatus
import java.time.Duration

data class FieldError(
    val field: String,
    val code: String,
    val message: String,
)

/**
 * A failure the client is expected to handle. `code` is the stable identifier clients branch on;
 * `message` is shown to people and may change.
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val fieldErrors: List<FieldError> = emptyList(),
    val retryAfter: Duration? = null,
) : RuntimeException(message)

class NotFoundException(
    code: String,
    message: String,
) : ApiException(HttpStatus.NOT_FOUND, code, message)

class ForbiddenException(
    message: String = "이 작업을 할 권한이 없습니다",
) : ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message)

class ConflictException(
    code: String,
    message: String,
) : ApiException(HttpStatus.CONFLICT, code, message)

class ValidationException(
    fieldErrors: List<FieldError>,
) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "입력값이 올바르지 않습니다", fieldErrors) {
    constructor(field: String, code: String, message: String) : this(listOf(FieldError(field, code, message)))
}

class UnauthenticatedException(
    code: String = "UNAUTHENTICATED",
    message: String = "로그인이 필요합니다",
) : ApiException(HttpStatus.UNAUTHORIZED, code, message)

class TooManyRequestsException(
    retryAfter: Duration,
) : ApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        "TOO_MANY_REQUESTS",
        "요청이 너무 많습니다. 잠시 후 다시 시도하세요",
        retryAfter = retryAfter,
    )
