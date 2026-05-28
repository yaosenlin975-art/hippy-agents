package com.lin.hippyagent.core.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelChoice
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ModelStreamChunk
import com.lin.hippyagent.core.model.ModelStreamChoice
import com.lin.hippyagent.core.model.ModelUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

class LiteRTLMEngine(
    private val modelPath: String,
    private val backendPref: BackendPreference,
    private val cacheDir: String,
    private val context: Context,
    private val capabilities: Set<OnDeviceCapability> = emptySet(),
) {
    private var engine: Engine? = null

    private val hasAudio: Boolean
        get() = OnDeviceCapability.AUDIO in capabilities
    private val hasVision: Boolean
        get() = OnDeviceCapability.VISION in capabilities

    suspend fun initialize() = withContext(Dispatchers.Default) {
        val backend = resolveBackend()
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir,
        )
        val eng = Engine(config)
        eng.initialize()
        engine = eng
        Timber.i("LiteRTLMEngine: initialized with backend=${backend::class.simpleName}, audio=$hasAudio, vision=$hasVision")
    }

    fun isReady(): Boolean = engine != null

    fun createConversation(config: ConversationConfig): Conversation {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        return eng.createConversation(config)
    }

    suspend fun generate(request: ModelCallRequest): ModelCallResponse = withContext(Dispatchers.Default) {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        val conv = eng.createConversation(buildConversationConfig(request))
        try {
            val lastUserMsg = request.messages.lastOrNull { it.role == "user" }
                ?: throw IllegalArgumentException("No user message in request")
            val response = conv.sendMessage(lastUserMsg.content)
            val estimatedInput = request.messages.sumOf { it.content.length / 4 }
            val estimatedOutput = response.toString().length / 4
            ModelCallResponse(
                id = UUID.randomUUID().toString(),
                choices = listOf(ModelChoice(
                    index = 0,
                    message = ModelMessage(role = "assistant", content = response.toString()),
                    finishReason = "stop"
                )),
                usage = ModelUsage(
                    promptTokens = estimatedInput,
                    completionTokens = estimatedOutput,
                    totalTokens = estimatedInput + estimatedOutput
                )
            )
        } finally {
            conv.close()
        }
    }

    fun generateStream(request: ModelCallRequest): Flow<ModelStreamChunk> = flow {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        val conv = eng.createConversation(buildConversationConfig(request))
        try {
            val lastUserMsg = request.messages.lastOrNull { it.role == "user" }
                ?: throw IllegalArgumentException("No user message in request")
            val requestId = UUID.randomUUID().toString()
            conv.sendMessageAsync(lastUserMsg.content)
                .collect { chunk ->
                    emit(ModelStreamChunk(
                        id = requestId,
                        choices = listOf(ModelStreamChoice(
                            index = 0,
                            delta = ModelMessage(role = "assistant", content = chunk.toString()),
                            finishReason = null
                        ))
                    ))
                }
            emit(ModelStreamChunk(
                id = requestId,
                choices = listOf(ModelStreamChoice(
                    index = 0,
                    delta = ModelMessage(role = "assistant", content = ""),
                    finishReason = "stop"
                ))
            ))
        } finally {
            conv.close()
        }
    }.flowOn(Dispatchers.Default)

    fun close() {
        runCatching { engine?.close() }
        engine = null
        Timber.i("LiteRTLMEngine: closed")
    }

    private fun buildConversationConfig(request: ModelCallRequest): ConversationConfig {
        val systemMsg = request.messages.firstOrNull { it.role == "system" }
        val samplerConfig = SamplerConfig(
            topK = 40,
            topP = (request.topP ?: 0.95f).toDouble(),
            temperature = (request.temperature ?: 0.8f).toDouble(),
        )
        return ConversationConfig(
            systemInstruction = systemMsg?.let { Contents.of(it.content) },
            samplerConfig = samplerConfig,
        )
    }

    private fun resolveBackend(): Backend {
        return when (backendPref) {
            BackendPreference.CPU -> Backend.CPU()
            BackendPreference.GPU -> Backend.GPU()
            BackendPreference.NPU -> Backend.NPU(
                nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            )
            BackendPreference.AUTO -> {
                runCatching { Backend.GPU() }
                    .getOrElse { Backend.CPU() }
            }
        }
    }
}
