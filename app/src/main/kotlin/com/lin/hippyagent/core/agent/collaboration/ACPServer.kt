package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Agent Communication Protocol (ACP) server.
 * Exposes an agent as an ACP endpoint via JSON-RPC for inter-system communication.
 */
class ACPServer(
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun start(port: Int = 8090): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isRunning) return@runCatching

            serverSocket = ServerSocket(port)
            isRunning = true
            Timber.i("ACP server started on port $port")

            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleConnection(clientSocket)
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "ACP connection error")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
        Timber.i("ACP server stopped")
    }

    fun isRunning(): Boolean = isRunning

    private suspend fun handleConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                OutputStreamWriter(socket.getOutputStream()).use { writer ->
                    val line = reader.readLine() ?: return@withContext
                    val request = json.parseToJsonElement(line).jsonObject

                    val method = request["method"]?.toString()?.removeSurrounding("\"") ?: ""
                    val params = request["params"]?.jsonObject
                    val id = request["id"]

                    val response = when (method) {
                        "agent/chat" -> handleChat(params)
                        "agent/list" -> handleListAgents()
                        "agent/status" -> handleStatus(params)
                        "agent/steer" -> handleSteer(params)
                        "agent/queue" -> handleQueue(params)
                        else -> mapOf("error" to "Unknown method: $method")
                    }

                    val responseStr = buildJsonObject {
                        response.forEach { (k, v) -> put(k, JsonPrimitive(v.toString())) }
                    }.toString()

                    val responseJson = """{"id":$id,"result":$responseStr}"""
                    writer.write(responseJson + "\n")
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "ACP connection handling failed")
        } finally {
            socket.close()
        }
    }

    private suspend fun handleChat(params: JsonObject?): Map<String, Any> {
        val agentId = params?.get("agent_id")?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing agent_id")
        val message = params["message"]?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing message")

        val agent = agentFactory.getAgent(agentId) ?: return mapOf("error" to "Agent not found: $agentId")

        val sessionId = "acp:${agentId}:${System.currentTimeMillis()}"
        val result = agent.processMessage(sessionId, "acp", message)

        return if (result.isSuccess) {
            mapOf("response" to "OK", "session_id" to sessionId)
        } else {
            mapOf("error" to (result.exceptionOrNull()?.message ?: "Unknown error"))
        }
    }

    private fun handleListAgents(): Map<String, Any> {
        val agents = agentFactory.getAllAgents().map { agent ->
            mapOf(
                "id" to agent.profileConfig.agentId,
                "name" to agent.profileConfig.name,
                "enabled" to agent.profileConfig.enabled
            )
        }
        return mapOf("agents" to agents)
    }

    private suspend fun handleStatus(params: JsonObject?): Map<String, Any> {
        val agentId = params?.get("agent_id")?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing agent_id")
        val agent = agentFactory.getAgent(agentId) ?: return mapOf("error" to "Agent not found: $agentId")
        return mapOf(
            "id" to agent.profileConfig.agentId,
            "name" to agent.profileConfig.name,
            "status" to if (agent.profileConfig.enabled) "online" else "disabled"
        )
    }

    private suspend fun handleSteer(params: JsonObject?): Map<String, Any> {
        val agentId = params?.get("agent_id")?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing agent_id")
        val direction = params["direction"]?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing direction")
        val agent = agentFactory.getAgent(agentId) ?: return mapOf("error" to "Agent not found: $agentId")
        val sessionId = params["session_id"]?.toString()?.removeSurrounding("\"")
        agent.steer(direction, sessionId)
        return mapOf("status" to "steered", "agent_id" to agentId, "direction" to direction)
    }

    private suspend fun handleQueue(params: JsonObject?): Map<String, Any> {
        val agentId = params?.get("agent_id")?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing agent_id")
        val message = params["message"]?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing message")
        val agent = agentFactory.getAgent(agentId) ?: return mapOf("error" to "Agent not found: $agentId")
        val sessionId = params["session_id"]?.toString()?.removeSurrounding("\"") ?: return mapOf("error" to "Missing session_id")
        val queueSize = agent.queueMessage(sessionId, message)
        return mapOf("status" to "queued", "agent_id" to agentId, "queue_size" to queueSize)
    }
}

