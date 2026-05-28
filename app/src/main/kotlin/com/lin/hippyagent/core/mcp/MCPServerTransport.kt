package com.lin.hippyagent.core.mcp

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

class MCPServerTransport(
    port: Int,
    private val requestHandler: (JsonObject) -> JsonObject
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                "application/json",
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Only POST allowed"}}"""
            )
        }

        if (session.uri != "/mcp") {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Not found, use POST /mcp"}}"""
            )
        }

        val body = runCatching { readBody(session) }
            .getOrElse { e ->
                Timber.e(e, "MCP Server: failed to read request body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error: ${e.message}"}}"""
                )
            }

        val requestElement = runCatching { json.parseToJsonElement(body) }
            .getOrElse { e ->
                Timber.e(e, "MCP Server: invalid JSON")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error: ${e.message}"}}"""
                )
            }

        val request = requestElement.jsonObject
        val response = requestHandler(request)
        val responseStr = response.toString()

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            responseStr
        )
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength == 0L) return ""

        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    fun startServer(): Result<Unit> = runCatching {
        start()
        Timber.i("MCP Server transport started on port ${listeningPort}")
    }

    fun stopServer() {
        stop()
        Timber.i("MCP Server transport stopped")
    }
}
