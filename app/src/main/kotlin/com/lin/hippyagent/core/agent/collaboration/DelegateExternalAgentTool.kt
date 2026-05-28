package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * 通过 ACP 协议委托任务给外部 Agent 的工具
 *
 * 支持两种模式：
 * 1. 指定 host:port — 直接连接指定地址
 * 2. 仅指定 agent_id — 从 AcpClientStore 已配置的服务器中自动匹配
 */
class DelegateExternalAgentTool(
    private val clientStore: AcpClientStore? = null
) : Tool() {
    override val definition = ToolDefinition(
        name = "delegate_external_agent",
        description = buildString {
            append("通过 ACP 协议委托任务给外部 Agent（远程服务器）。")
            if (clientStore != null) {
                val servers = clientStore.getEnabledServers()
                if (servers.isNotEmpty()) {
                    val agentList = servers.flatMap { s ->
                        s.discoveredAgents.filter { it.enabled }.map { "${it.name}@${s.name}" }
                    }
                    if (agentList.isNotEmpty()) {
                        append("可用的外部智能体: ${agentList.joinToString(", ")}。")
                    }
                }
            }
            append("如果已配置远程服务器，只需提供 agent_id 即可自动连接。")
        },
        parameters = mapOf(
            "agent_id" to ToolParameter(
                name = "agent_id",
                type = "string",
                description = "外部 Agent ID（如已配置服务器，可直接使用 agent_id 自动匹配）",
                required = true
            ),
            "message" to ToolParameter(
                name = "message",
                type = "string",
                description = "发送的消息内容",
                required = true
            ),
            "host" to ToolParameter(
                name = "host",
                type = "string",
                description = "外部 Agent 的主机地址（可选，已配置服务器时无需提供）",
                required = false
            ),
            "port" to ToolParameter(
                name = "port",
                type = "integer",
                description = "外部 Agent 的端口号（可选，默认 8090）",
                required = false
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val agentId = getRequiredArgument(arguments, "agent_id")
        val message = getRequiredArgument(arguments, "message")
        val callId = arguments["callId"] as? String ?: ""

        // 解析 host/port：优先使用参数，其次从配置自动匹配
        val explicitHost = arguments["host"] as? String
        val explicitPort = (arguments["port"] as? Number)?.toInt()

        val host: String
        val port: Int

        if (explicitHost != null) {
            host = explicitHost
            port = explicitPort ?: 8090
        } else if (clientStore != null) {
            // 从已配置的服务器中查找包含该 agent_id 的服务器
            val match = findServerForAgent(agentId)
            if (match != null) {
                host = match.first.host
                port = match.first.port
            } else {
                return ToolResult(callId, false,
                    error = "未找到包含 agent '${agentId}' 的已配置服务器。请先在 ACP 设置中添加服务器并发现智能体，或手动指定 host/port。")
            }
        } else {
            return ToolResult(callId, false,
                error = "ACP 客户端未初始化，且未指定 host。")
        }

        return try {
            val response = delegateViaACP(host, port, agentId, message)
            ToolResult(callId, true, response)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delegate to external agent $agentId at $host:$port")
            ToolResult(callId, false, error = "Error: ${e.message}")
        }
    }

    private fun findServerForAgent(agentId: String): Pair<AcpRemoteServer, DiscoveredAgent>? {
        if (clientStore == null) return null
        for (server in clientStore.getEnabledServers()) {
            val agent = server.discoveredAgents.find { it.agentId == agentId && it.enabled }
            if (agent != null) return server to agent
        }
        return null
    }

    /**
     * 发现远程服务器上的所有智能体
     */
    suspend fun discoverAgents(host: String, port: Int): Result<List<DiscoveredAgent>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket(host, port).use { socket ->
                    socket.soTimeout = 10_000
                    val writer = OutputStreamWriter(socket.getOutputStream())
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    val request = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "agent/list")
                        put("id", "discover")
                        put("params", buildJsonObject {})
                    }

                    writer.write(request.toString() + "\n")
                    writer.flush()

                    val responseLine = reader.readLine() ?: throw Exception("No response")
                    val response = json.parseToJsonElement(responseLine).jsonObject

                    response["error"]?.let { throw Exception("Error: $it") }

                    val result = response["result"]?.jsonObject ?: throw Exception("No result")
                    val agents = result["agents"]?.toString() ?: "[]"
                    json.decodeFromString<List<DiscoveredAgent>>(agents)
                }
            }
        }

    private suspend fun delegateViaACP(
        host: String, port: Int, agentId: String, message: String
    ): String = withContext(Dispatchers.IO) {
        Socket(host, port).use { socket ->
            socket.soTimeout = 30_000
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val request = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "agent/chat")
                put("id", "1")
                put("params", buildJsonObject {
                    put("agent_id", agentId)
                    put("message", message)
                })
            }

            writer.write(request.toString() + "\n")
            writer.flush()

            val responseLine = reader.readLine() ?: throw Exception("No response from external agent")
            val response = json.parseToJsonElement(responseLine).jsonObject

            response["error"]?.toString()?.let { error ->
                throw Exception("External agent error: $error")
            }

            response["result"]?.toString() ?: response.toString()
        }
    }
}

