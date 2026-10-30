package com.flowbit.mcp

import com.flowbit.common.error.ValidationException

internal fun issueNumber(value: Any?): Int {
    val text = value as? String ?: throw ValidationException("issue", "INVALID", "티켓 번호 또는 키를 입력하세요")
    if (!Regex("(?:[\\p{L}\\d_]+-)?[1-9]\\d*").matches(text)) throw ValidationException("issue", "INVALID", "티켓 번호 또는 키를 입력하세요")
    return text.substringAfterLast('-').toIntOrNull() ?: throw ValidationException("issue", "INVALID", "티켓 번호가 올바르지 않습니다")
}
