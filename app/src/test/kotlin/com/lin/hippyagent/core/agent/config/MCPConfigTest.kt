package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class MCPConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `test default values`() {
        val config = MCPConfig()
        assertTrue(config.clients.isEmpty())
    }

    @Test
    fun `test stdio client serialization`() {
        val clientConfig = MCPClientConfig(
            name = "tavily_search",
            transport = "stdio",
            command = "npx",
            args = listOf("-y", "tavily-mcp@latest"),
            env = mapOf("TAVILY_API_KEY" to "sk-test-key")
        )
        val config = MCPConfig(clients = mapOf("tavily" to clientConfig))
        val jsonString = json.encodeToString(config)
        val decoded = json.decodeFromString<MCPConfig>(jsonString)
        assertEquals(1, decoded.clients.size)
        val tavilyClient = decoded.clients["tavily"]
        assertNotNull(tavilyClient)
        assertEquals("tavily_search", tavilyClient!!.name)
        assertEquals("stdio", tavilyClient.transport)
        assertEquals("npx", tavilyClient.command)
        assertEquals(2, tavilyClient.args.size)
    }

    @Test
    fun `test http client serialization`() {
        val clientConfig = MCPClientConfig(
            name = "remote_mcp",
            transport = "streamable_http",
            url = "https://mcp.example.com/sse",
            headers = mapOf("Authorization" to "Bearer token")
        )
        val config = MCPConfig(clients = mapOf("remote" to clientConfig))
        val jsonString = json.encodeToString(config)
        val decoded = json.decodeFromString<MCPConfig>(jsonString)
        val remoteClient = decoded.clients["remote"]
        assertNotNull(remoteClient)
        assertEquals("streamable_http", remoteClient!!.transport)
        assertEquals("https://mcp.example.com/sse", remoteClient.url)
    }

    @Test
    fun `test compatibility with multiple formats`() {
        val standardFormat = """
        {
            "mcpServers": {
                "tavily": {
                    "name": "tavily_search",
                    "enabled": true,
                    "transport": "stdio",
                    "command": "npx",
                    "args": ["-y", "tavily-mcp@latest"]
                }
            }
        }
        """.trimIndent()

        // [PENDING_USER_DECISION] 是否需要支持标准格式自动解析为 MCPConfig?
        // 当前只支持直接格式: { "clients": {...} }
        // 可选: 添加解析器支持 mcpServers 包装格式
    }
}

