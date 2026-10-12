package com.flowbit.user.domain

import com.flowbit.common.error.FieldError
import java.util.Locale

/** Rules for login ids and passwords (docs/features/M1-auth-and-setup.md, "입력 검증"). */
object CredentialPolicy {
    const val LOGIN_ID_MIN_LENGTH = 4
    const val LOGIN_ID_MAX_LENGTH = 32
    const val PASSWORD_MIN_LENGTH = 10
    const val PASSWORD_MAX_LENGTH = 128

    private val loginIdPattern = Regex("^[a-z0-9][a-z0-9._-]*$")

    fun normalizeLoginId(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    /** `loginId` must already be normalized. */
    fun validate(
        loginId: String,
        password: String,
    ): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        when {
            loginId.length < LOGIN_ID_MIN_LENGTH -> {
                errors += FieldError("loginId", "TOO_SHORT", "아이디는 ${LOGIN_ID_MIN_LENGTH}자 이상이어야 합니다")
            }

            loginId.length > LOGIN_ID_MAX_LENGTH -> {
                errors += FieldError("loginId", "TOO_LONG", "아이디는 ${LOGIN_ID_MAX_LENGTH}자 이하여야 합니다")
            }

            !loginIdPattern.matches(loginId) -> {
                errors +=
                    FieldError(
                        "loginId",
                        "INVALID_FORMAT",
                        "아이디는 영문 소문자, 숫자, 마침표(.), 밑줄(_), 하이픈(-)만 쓸 수 있고 영문이나 숫자로 시작해야 합니다",
                    )
            }
        }
        when {
            password.length < PASSWORD_MIN_LENGTH -> {
                errors += FieldError("password", "TOO_SHORT", "비밀번호는 ${PASSWORD_MIN_LENGTH}자 이상이어야 합니다")
            }

            password.length > PASSWORD_MAX_LENGTH -> {
                errors += FieldError("password", "TOO_LONG", "비밀번호는 ${PASSWORD_MAX_LENGTH}자 이하여야 합니다")
            }

            password.lowercase(Locale.ROOT) == loginId -> {
                errors += FieldError("password", "SAME_AS_LOGIN_ID", "아이디와 같은 비밀번호는 쓸 수 없습니다")
            }
        }
        return errors
    }
}
