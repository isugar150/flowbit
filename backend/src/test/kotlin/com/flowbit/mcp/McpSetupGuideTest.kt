package com.flowbit.mcp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSetupGuideTest {
    @Test
    fun `공개 가이드는 설치 주소로 JSON 설정과 검증 주소를 완성한다`() =
        runTest {
            val guide = McpSetupGuide(ClassPathResource("mcp/setup.md"), JsonMapper.builder().build())
            val text = guide.render("https://flowbit.example.com/")
            assertTrue(text.contains("url = \"https://flowbit.example.com/api/v1/mcp\""))
            assertTrue(text.contains("\"url\": \"https://flowbit.example.com/api/v1/mcp\""))
            assertTrue(text.contains("https://flowbit.example.com/api/v1/integrations/mcp/tools/list_projects"))
            assertTrue(text.contains("https://flowbit.example.com/settings/mcp"))
            assertFalse(text.contains("{{"))
            assertFalse(text.contains("localhost"))
        }
}
