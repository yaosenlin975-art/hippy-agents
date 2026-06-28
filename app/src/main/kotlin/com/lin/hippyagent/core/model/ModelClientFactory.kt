package com.lin.hippyagent.core.model

import com.lin.hippyagent.core.ondevice.LiteRTLMModelClient
import com.lin.hippyagent.core.ondevice.OnDeviceModelManager
import com.lin.hippyagent.core.storage.SecureStorage
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanContext
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceContextElement
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.coroutineContext

object ModelClientFactory {
    fun create(
        provider: ModelProvider,
        secureStorage: SecureStorage? = null,
        onDeviceModelManager: OnDeviceModelManager? = null
    ): ModelClient {
        val apiKey = secureStorage?.getApiKey(provider.id) ?: provider.apiKey
        val delegate = when (provider.protocol) {
            "anthropic" -> AnthropicModelClient(
                baseUrl = provider.baseUrl,
                apiKey = apiKey
            )
            "ollama" -> OllamaModelClient(
                baseUrl = provider.baseUrl
            )
            "litertlm" -> {
                val manager = onDeviceModelManager
                    ?: throw IllegalStateException("OnDeviceModelManager not available for litertlm protocol")
                val modelId = provider.baseUrl.removePrefix("litertlm://")
                LiteRTLMModelClient(manager, modelId)
            }
            else -> OpenAIModelClient(
                baseUrl = provider.baseUrl,
                apiKey = apiKey
            )
        }
        return TracingModelClient(delegate, provider.id)
    }
}

private class TracingModelClient(
    private val delegate: ModelClient,
    private val providerId: String
) : ModelClient {
    override suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse {
        val traceCtx = coroutineContext[TraceContextElement]
        val span = if (traceCtx != null) {
            SpanCollector.startSpan(
                type = SpanType.LLM_CALL,
                traceId = traceCtx.traceId,
                parentSpanId = traceCtx.parentSpanId,
                props = mapOf(
                    "modelId" to (request.model.ifBlank { "unknown" }),
                    "providerId" to providerId,
                    "requestMessages" to request.messages
                )
            )
        } else {
            SpanContext.NoOp
        }
        try {
            val response = delegate.chatCompletion(request)
            SpanCollector.end(span, extraProps = mapOf(
                "promptTokens" to (response.usage?.promptTokens ?: 0),
                "completionTokens" to (response.usage?.completionTokens ?: 0),
                "finishReason" to (response.choices.firstOrNull()?.finishReason ?: "unknown"),
                "responseText" to (response.choices.firstOrNull()?.message?.content ?: "")
            ))
            return response
        } catch (e: Exception) {
            SpanCollector.end(span, error = e.message)
            throw e
        }
    }

    override suspend fun chatCompletionStream(request: ModelCallRequest): Flow<ModelStreamChunk> =
        delegate.chatCompletionStream(request)

    override suspend fun testConnection(): Result<Unit> = delegate.testConnection()

    override suspend fun listModels(): List<String> = delegate.listModels()
}
