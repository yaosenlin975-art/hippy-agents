package com.lin.hippyagent.core.mcp

import com.lin.hippyagent.core.tools.DeferredToolRegistry
import com.lin.hippyagent.core.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

class MCPToolRegistrar(
    private val mcpClientManager: MCPClientManager,
    private val deferredToolRegistry: DeferredToolRegistry? = null
) {
    private val registeredMcpTools = mutableMapOf<String, String>()

    suspend fun registerToolsFromClient(clientName: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = mcpClientManager.getClient(clientName)
                ?: throw IllegalArgumentException("MCP client not found: $clientName")

            val toolsResult = client.listTools().getOrDefault("")
            if (toolsResult.isBlank()) return@runCatching emptyList()

            val toolNames = parseAndRegisterTools(clientName, toolsResult)
            Timber.i("MCPToolRegistrar: registered $toolNames from client $clientName")
            toolNames
        }
    }

    suspend fun registerAllClients(): Result<Map<String, List<String>>> = withContext(Dispatchers.IO) {
        runCatching {
            val results = mutableMapOf<String, List<String>>()
            for (clientName in mcpClientManager.listClients()) {
                val tools = registerToolsFromClient(clientName).getOrDefault(emptyList())
                results[clientName] = tools
            }
            results
        }
    }

    private fun parseAndRegisterTools(clientName: String, toolsJson: String): List<String> {
        val registered = mutableListOf<String>()
        try {
            val json = Json { ignoreUnknownKeys = true }
            val response = json.parseToJsonElement(toolsJson).jsonObject
            val toolsArray = response["result"]?.jsonObject?.get("tools")
                ?: return emptyList()

            if (toolsArray !is JsonArray) return emptyList()

            for (toolElement in toolsArray) {
                val toolObj = toolElement.jsonObject
                val toolName = toolObj["name"]?.jsonPrimitive?.content ?: continue
                val description = toolObj["description"]?.jsonPrimitive?.content ?: ""
                val fullName = "mcp_${clientName}_$toolName"

                val parameters = mutableMapOf<String, ToolParameter>()
                val inputSchema = toolObj["inputSchema"]?.jsonObject
                if (inputSchema != null) {
                    val properties = inputSchema["properties"]?.jsonObject
                    val required = inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                    properties?.forEach { (paramName, paramValue) ->
                        val paramObj = paramValue.jsonObject
                        val paramType = paramObj["type"]?.jsonPrimitive?.content ?: "string"
                        val paramDesc = paramObj["description"]?.jsonPrimitive?.content ?: ""
                        parameters[paramName] = ToolParameter(
                            name = paramName,
                            type = paramType,
                            description = paramDesc,
                            required = paramName in required
                        )
                    }
                }

                val mcpToolDef = com.lin.hippyagent.core.model.ModelToolDefinition(
                    name = fullName,
                    description = description,
                    parameters = buildParameterSchema(parameters)
                )
                deferredToolRegistry?.register(mcpToolDef)
                registeredMcpTools[fullName] = clientName
                registered.add(fullName)
            }
        } catch (e: Exception) {
            Timber.e(e, "MCPToolRegistrar: failed to parse tools from $clientName")
        }
        return registered
    }

    fun getRegisteredToolNames(): Set<String> = registeredMcpTools.keys.toSet()

    fun getClientForTool(toolName: String): String? = registeredMcpTools[toolName]

    fun unregisterClient(clientId: String) {
        val iterator = registeredMcpTools.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == clientId) {
                iterator.remove()
            }
        }
    }

    private fun buildParameterSchema(parameters: Map<String, ToolParameter>): Map<String, Any> {
        if (parameters.isEmpty()) return emptyMap()
        val properties = mutableMapOf<String, Any>()
        val required = mutableListOf<String>()
        for ((name, param) in parameters) {
            properties[name] = mapOf(
                "type" to param.type,
                "description" to param.description
            )
            if (param.required) required.add(name)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }
}
