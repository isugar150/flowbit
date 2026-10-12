package com.flowbit.user.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CredentialPolicyTest {
    private val password = "correct-horse-battery"

    @Test
    fun `아이디는 앞뒤 공백을 지우고 소문자로 바꾼다`() {
        assertEquals("admin.kim", CredentialPolicy.normalizeLoginId("  Admin.Kim "))
    }

    @Test
    fun `규칙에 맞는 아이디와 비밀번호는 오류가 없다`() {
        listOf("admin", "kim.jm", "dev_01", "a-b-c", "0user", "a".repeat(32), "kim@example.com", "kim.jm+dev@mail.example.co.kr").forEach {
            assertTrue(CredentialPolicy.validate(it, password).isEmpty(), it)
        }
    }

    @Test
    fun `아이디 길이와 문자 규칙을 검사한다`() {
        assertEquals("TOO_SHORT", codeFor("loginId", "abc", password))
        assertEquals("TOO_LONG", codeFor("loginId", "a".repeat(33), password))
        listOf(".admin", "_admin", "ad min", "관리자입니다", "admin!").forEach {
            assertEquals("INVALID_FORMAT", codeFor("loginId", it, password), it)
        }
    }

    @Test
    fun `골뱅이가 들어간 아이디는 이메일 주소여야 한다`() {
        listOf("admin@home", "@example.com", "kim@", "kim@@example.com", "kim@exa mple.com").forEach {
            assertEquals("INVALID_EMAIL", codeFor("loginId", it, password), it)
        }
    }

    @Test
    fun `비밀번호 길이를 검사하고 아이디와 같은 값은 거부한다`() {
        assertEquals("TOO_SHORT", codeFor("password", "admin", "123456789"))
        assertEquals("TOO_LONG", codeFor("password", "admin", "p".repeat(129)))
        assertEquals("SAME_AS_LOGIN_ID", codeFor("password", "administrator", "ADMINISTRATOR"))
        assertTrue(CredentialPolicy.validate("admin", "p".repeat(128)).isEmpty())
    }

    private fun codeFor(
        field: String,
        loginId: String,
        password: String,
    ): String? = CredentialPolicy.validate(loginId, password).singleOrNull { it.field == field }?.code
}
