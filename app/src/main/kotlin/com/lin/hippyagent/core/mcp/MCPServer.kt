package com.lin.hippyagent.core.mcp

import com.lin.hippyagent.core.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber

class MCPServer(
    private val toolRegistry: Map<String, Tool>,
    private val port: Int = 8091
) {
    private var transport: MCPServerTransport? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun start(): Result<Unit> = runCatching {
        val t = MCPServerTransport(port, ::handleRequest)
        t.startServer().getOrThrow()
        transport = t
        Timber.i("MCP Server started on port $port with ${toolRegistry.size} tools")
    }

    fun stop() {
        transport?.stopServer()
        transport = null
        Timber.i("MCP Server stopped")
    }

    val isRunning: Boolean get() = transport != null

    private fun handleRequest(request: JsonObject): JsonObject {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content
        val params = request["params"]?.jsonObject ?: buildJsonObject {}

        Timber.d("MCP Server request: method=$method id=$id")

        return when (method) {
            "initialize" -> handleInitialize(id)
            "ping" -> handlePing(id)
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, params)
            else -> {
                Timber.w("MCP Server: unknown method=$method")
                errorResponse(id, -32601, "Method not found")
            }
        }
    }

    private fun handleInitialize(id: JsonElement?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", false)
                })
            })
            put("serverInfo", buildJsonObject {
                put("name", "hippyagent-mcp-server")
                put("version", "1.0.0")
            })
        })
    }

    private fun handlePing(id: JsonElement?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", buildJsonObject {})
    }

    private fun handleToolsList(id: JsonElement?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", buildJsonObject {
            put("tools", buildJsonArray {
                toolRegistry.values.forEach { tool ->
                    val def = tool.definition
                    add(buildJsonObject {
                        put("name", def.name)
                        put("description", def.description)
                        put("inputSchema", buildInputSchema(def.parameters))
                    })
                }
            })
        })
    }

    private fun handleToolsCall(id: JsonElement?, params: JsonObject): JsonObject {
        val toolName = params["name"]?.jsonPrimitive?.content
        if (toolName.isNullOrBlank()) {
            return errorResponse(id, -32602, "Missing tool name")
        }

        val tool = toolRegistry[toolName]
        if (tool == null) {
            Timber.w("MCP Server: tool not found: $toolName")
            return errorResponse(id, -32602, "Tool not found: $toolName")
        }

        val arguments = params["arguments"]?.jsonObject?.let { obj ->
            obj.mapValues { (_, v) -> jsonElementToAny(v) }
        } ?: emptyMap()

        return runCatching {
            val result = runBlocking(Dispatchers.IO) {
                tool.execute(arguments)
            }
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                put("result", buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", result.contentForLLM())
                        })
                    })
                    put("isError", !result.success)
                })
            }
        }.getOrElse { e ->
            Timber.e(e, "MCP Server: tool execution failed: $toolName")
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                put("result", buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Error: ${e.message}")
                        })
                    })
                    put("isError", true)
                })
            }
        }
    }

    private fun buildInputSchema(parameters: Map<String, com.lin.hippyagent.core.tools.ToolParameter>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            parameters.forEach { (name, param) ->
                put(name, buildJsonObject {
                    put("type", mapToolType(param.type))
                    put("description", param.description)
                })
            }
        })
        val required = parameters.filter { it.value.required }.keys.toList()
        if (required.isNotEmpty()) {
            put("required", buildJsonArray {
                required.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun mapToolType(type: String): String = when (type.lowercase()) {
        "string" -> "string"
        "number", "integer", "int", "long", "float", "double" -> "number"
        "boolean", "bool" -> "boolean"
        "array", "list" -> "array"
        "object", "map" -> "object"
        else -> "string"
    }

    private fun jsonElementToAny(element: JsonElement): Any {
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content.toIntOrNull() != null -> element.content.toInt()
                    element.content.toLongOrNull() != null -> element.content.toLong()
                    element.content.toDoubleOrNull() != null -> element.content.toDouble()
                    else -> element.content
                }
            }
            is JsonArray -> element.map { jsonElementToAny(it) }
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
            else -> element.toString()
        }
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }
}
