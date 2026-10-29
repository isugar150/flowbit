package com.flowbit.mcp

import com.flowbit.common.web.PublicUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.json.JsonMapper

const val MCP_SETUP_PATH = "$MCP_PATH/setup"

/** Public installation instructions contain only endpoint addresses and credential placeholders. */
@Service
class McpSetupGuide(
    @Value("classpath:mcp/setup.md") private val template: Resource,
    private val json: JsonMapper,
) {
    suspend fun render(baseUrl: String): String {
        val source = withContext(Dispatchers.IO) { template.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
        val base = baseUrl.trimEnd('/')
        val mcpUrl = base + MCP_PATH
        return source
            .replace("{{MCP_URL}}", mcpUrl)
            .replace("{{MCP_URL_JSON}}", json.writeValueAsString(mcpUrl))
            .replace("{{SETTINGS_URL}}", "$base/settings/mcp")
            .replace("{{TOOLS_URL}}", "$base/api/v1/integrations/mcp/tools")
            .replace("{{LIST_PROJECTS_URL}}", "$base/api/v1/integrations/mcp/tools/list_projects")
    }
}

@RestController
class McpSetupGuideController(
    private val guide: McpSetupGuide,
    private val publicUrl: PublicUrl,
) {
    @GetMapping(MCP_SETUP_PATH, produces = ["text/markdown;charset=UTF-8"])
    suspend fun setup(exchange: ServerWebExchange): String {
        exchange.response.headers.cacheControl = "no-store"
        exchange.response.headers.set("X-Robots-Tag", "noindex, nofollow")
        val uri = exchange.request.uri
        val base = publicUrl.base ?: "${uri.scheme}://${uri.rawAuthority}"
        return guide.render(base)
    }
}
