package com.lin.hippyagent.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

class MCPClientManager(
    private val mcpToolRegistrar: MCPToolRegistrar? = null
) {
    private val clients = mutableMapOf<String, MCPClient>()

    suspend fun addClient(name: String, transport: String, config: Map<String, String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = MCPClient(name, transport, config)
            clients[name] = client
            Timber.i("MCP client added: $name")
        }
    }

    suspend fun removeClient(name: String) {
        clients[name]?.disconnect()
        clients.remove(name)
        mcpToolRegistrar?.unregisterClient(name)
        Timber.i("MCP client removed: $name")
    }

    fun getClient(name: String): MCPClient? = clients[name]

    fun listClients(): List<String> = clients.keys.toList()
}

class MCPClient(
    val name: String,
    val transport: String,
    val config: Map<String, String>
) {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var idCounter = 0

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private var sseEventSource: EventSource? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (transport == "stdio") {
            val command = config["command"] ?: throw IllegalArgumentException("Command required for stdio")
            val args = config["args"]?.split(" ") ?: emptyList()
            val env = config["env"]?.let { parseEnvString(it) } ?: emptyMap()
            process = ProcessBuilder(listOf(command) + args).apply {
                environment().putAll(env)
            }.start()
            val proc = process ?: throw IllegalStateException("MCP process not started for $name")
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            writer = OutputStreamWriter(proc.outputStream)
            Timber.i("MCP client connected: $name")
        } else if (transport == "streamable_http" || transport == "sse") {
            val baseUrl = config["baseUrl"] ?: config["url"] ?: throw IllegalArgumentException("baseUrl required")
            Timber.i("MCP client connected via $transport: $name -> $baseUrl")
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        sseEventSource?.cancel()
        sseEventSource = null
        process?.destroy()
        reader?.close()
        writer?.close()
        Timber.i("MCP client disconnected: $name")
    }

    suspend fun callTool(toolName: String, arguments: Map<String, Any>): Result<String> = withContext(Dispatchers.IO) {
        val timeout = config["execution_timeout_ms"]?.toLongOrNull() ?: 60_000L
        withTimeoutOrNull(timeout) {
            runCatching {
                val id = ++idCounter
                val json = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$toolName","arguments":${mapToJson(arguments)}}}"""
                when (transport) {
                    "stdio" -> sendStdio(json)
                    "streamable_http" -> sendStreamableHttp(json)
                    "sse" -> sendSse(json)
                    else -> throw IllegalArgumentException("Unsupported transport: $transport")
                }
            }
        } ?: Result.failure(IllegalStateException("MCP callTool timed out after ${timeout}ms: $toolName"))
    }

    suspend fun listTools(): Result<String> = withContext(Dispatchers.IO) {
        val timeout = config["execution_timeout_ms"]?.toLongOrNull() ?: 60_000L
        withTimeoutOrNull(timeout) {
            runCatching {
                val id = ++idCounter
                val json = """{"jsonrpc":"2.0","id":$id,"method":"tools/list","params":{}}"""
                when (transport) {
                    "stdio" -> sendStdio(json)
                    "streamable_http" -> sendStreamableHttp(json)
                    "sse" -> sendSse(json)
                    else -> throw IllegalArgumentException("Unsupported transport: $transport")
                }
            }
        } ?: Result.failure(IllegalStateException("MCP listTools timed out after ${timeout}ms"))
    }

    private fun sendStdio(json: String): String {
        writer?.write(json + "\n")
        writer?.flush()
        return reader?.readLine() ?: ""
    }

    private fun sendStreamableHttp(json: String): String {
        val baseUrl = config["baseUrl"] ?: config["url"] ?: ""
        val url = if (baseUrl.endsWith("/mcp")) baseUrl else "${baseUrl.trimEnd('/')}/mcp"

        val body = json.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)

        applyHeaders(requestBuilder)

        val response = httpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("MCP HTTP call failed: HTTP ${response.code} - ${response.body?.string()?.take(200)}")
        }
        return response.body?.string() ?: ""
    }

    private fun sendSse(json: String): String {
        val baseUrl = config["baseUrl"] ?: config["url"] ?: ""
        val url = if (baseUrl.endsWith("/mcp")) baseUrl else "${baseUrl.trimEnd('/')}/mcp"

        val body = json.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)

        applyHeaders(requestBuilder)

        val result = StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)
        var error: Throwable? = null

        sseEventSource = EventSources.createFactory(httpClient)
            .newEventSource(requestBuilder.build(), object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    result.append(data)
                    latch.countDown()
                }

                override fun onClosed(eventSource: EventSource) {
                    latch.countDown()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    error = t
                    latch.countDown()
                }
            })

        latch.await(60, TimeUnit.SECONDS)
        error?.let { throw it }
        return result.toString()
    }

    private fun applyHeaders(requestBuilder: Request.Builder) {
        config["headers"]?.let { headersStr ->
            try {
                val json = Json { ignoreUnknownKeys = true }
                val headersObj = json.parseToJsonElement(headersStr).jsonObject
                headersObj.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value.jsonPrimitive.content)
                }
            } catch (_: Exception) {}
        }
    }

    private fun parseEnvString(envStr: String): Map<String, String> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(envStr).jsonObject
            obj.mapValues { it.value.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mapToJson(map: Map<String, Any>): String {
        return map.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${when (v) {
                is String -> "\"${v.replace("\"", "\\\"")}\""
                is Boolean, is Number -> v.toString()
                is Map<*, *> -> mapToJson(v as Map<String, Any>)
                is List<*> -> v.joinToString(",", "[", "]") { "\"$it\"" }
                else -> "\"$v\""
            }}"
        }
    }
}

