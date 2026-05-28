package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MCPAliasParser {

    private val fieldAliases = mapOf(
        "isActive" to "enabled",
        "baseUrl" to "url",
        "type" to "transport"
    )

    private val transportAliases = mapOf(
        "streamablehttp" to "streamable_http",
        "http" to "streamable_http"
    )

    fun parseMCPConfig(json: JsonObject): MCPConfig {
        val clients = mutableMapOf<String, MCPClientConfig>()

        val mcpServers = json["mcpServers"]?.jsonObject
        if (mcpServers != null) {
            parseClientsFromObject(mcpServers, clients)
        }

        val directClients = json.filterKeys { key ->
            key != "mcpServers" && json[key] is JsonObject
        }
        if (directClients.isNotEmpty() && clients.isEmpty()) {
            parseClientsFromObject(JsonObject(directClients), clients)
        }

        json.filterKeys { key ->
            key != "mcpServers" && json[key] is JsonPrimitive
        }.takeIf { it.isNotEmpty() && clients.isEmpty() }?.let { singleClient ->
            parseSingleClientFormat(singleClient, clients)
        }

        return MCPConfig(clients = clients)
    }

    private fun parseClientsFromObject(
        obj: JsonObject,
        clients: MutableMap<String, MCPClientConfig>
    ) {
        obj.forEach { (key, element) ->
            if (element is JsonObject) {
                val resolved = resolveAliases(element)
                val client = parseClientConfig(resolved)
                clients[key] = client
            }
        }
    }

    private fun parseSingleClientFormat(
        fields: Map<String, JsonElement>,
        clients: MutableMap<String, MCPClientConfig>
    ) {
        val key = fields["key"]?.jsonPrimitive?.content ?: "default"
        val name = fields["name"]?.jsonPrimitive?.content ?: key
        val command = fields["command"]?.jsonPrimitive?.content ?: ""
        val url = fields["url"]?.jsonPrimitive?.content ?: ""

        val transport = when {
            url.isNotEmpty() && command.isEmpty() -> "streamable_http"
            else -> "stdio"
        }

        clients[key] = MCPClientConfig(
            name = name,
            transport = transport,
            command = command,
            url = url
        )
    }

    private fun resolveAliases(obj: JsonObject): JsonObject {
        val resolved = obj.toMutableMap()

        fieldAliases.forEach { (alias, target) ->
            if (alias in resolved && target !in resolved) {
                resolved[target] = resolved[alias]!!
            }
            resolved.remove(alias)
        }

        resolved["transport"]?.jsonPrimitive?.content?.let { transport ->
            val resolvedTransport = transportAliases[transport] ?: transport
            if (resolvedTransport != transport) {
                resolved["transport"] = JsonPrimitive(resolvedTransport)
            }
        }

        if ("url" in resolved && "command" !in resolved && "transport" !in resolved) {
            resolved["transport"] = JsonPrimitive("streamable_http")
        }

        return JsonObject(resolved)
    }

    private fun parseClientConfig(obj: JsonObject): MCPClientConfig {
        return MCPClientConfig(
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            description = obj["description"]?.jsonPrimitive?.content ?: "",
            enabled = obj["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true,
            transport = obj["transport"]?.jsonPrimitive?.content ?: "stdio",
            url = obj["url"]?.jsonPrimitive?.content ?: "",
            headers = parseStringMap(obj["headers"]?.jsonObject),
            command = obj["command"]?.jsonPrimitive?.content ?: "",
            args = parseStringList(obj["args"]),
            env = parseStringMap(obj["env"]?.jsonObject),
            cwd = obj["cwd"]?.jsonPrimitive?.content ?: ""
        )
    }

    private fun parseStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.mapValues { it.value.jsonPrimitive.content }
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        return try {
            kotlinx.serialization.json.JsonArray(listOf(element)).map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

