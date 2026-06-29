package com.lin.hippyagent.core.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.lin.hippyagent.core.model.FunctionInfo
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelChoice
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ModelStreamChunk
import com.lin.hippyagent.core.model.ModelStreamChoice
import com.lin.hippyagent.core.model.ModelUsage
import com.lin.hippyagent.core.model.ToolCallInfo
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
            // 修复缺陷 1：遍历所有消息按角色发送，维持多轮上下文
            // system 消息已在 buildConversationConfig 中作为 systemInstruction 传入，跳过
            // 注意：LiteRT-LM Conversation API 无 addResponse/lastResponse，
            // assistant 消息无法手动注入（Conversation 内部通过上一次 sendMessage 自动维护），
            // 故 assistant 消息跳过；最后一次 sendMessage 返回值即最终响应
            require(request.messages.any { it.role == "user" }) {
                "No user message in request"
            }
            var response: com.google.ai.edge.litertlm.Message? = null
            for (msg in request.messages.filter { it.role != "system" }) {
                when (msg.role) {
                    "user" -> response = conv.sendMessage(msg.content)
                    "assistant" -> {
                        // LiteRT-LM 无 addResponse 方法，跳过历史 assistant 回复
                        // Conversation 内部已通过上一次 sendMessage 维护 assistant 回复
                    }
                    "tool" -> {
                        // 工具结果作为 user 消息注入（端侧模型不理解 tool 角色）
                        response = conv.sendMessage("[工具结果] ${msg.content}")
                    }
                }
            }
            val responseText = response?.toString() ?: ""
            val estimatedInput = request.messages.sumOf { it.content.length / 4 }
            val estimatedOutput = responseText.length / 4

            // 修复缺陷 2：解析 tool_call
            val parsedToolCalls = OnDeviceToolCallParser.parseToolCalls(responseText)
            val cleanContent = OnDeviceToolCallParser.stripToolCallBlocks(responseText)
            val toolCalls = if (parsedToolCalls.isNotEmpty()) {
                parsedToolCalls.mapIndexed { idx, tc ->
                    ToolCallInfo(
                        id = "ondevice_${UUID.randomUUID()}",
                        type = "function",
                        function = FunctionInfo(
                            name = tc.name,
                            arguments = tc.arguments.toString()
                        ),
                        index = idx
                    )
                }
            } else null

            ModelCallResponse(
                id = UUID.randomUUID().toString(),
                choices = listOf(ModelChoice(
                    index = 0,
                    message = ModelMessage(
                        role = "assistant",
                        content = cleanContent,
                        toolCalls = toolCalls
                    ),
                    finishReason = if (toolCalls != null) "tool_calls" else "stop"
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
        val systemInstruction = buildString {
            systemMsg?.let { append(it.content) }
            // 注入工具描述（B2/B3 依赖 tool_call 模拟）
            if (!request.tools.isNullOrEmpty()) {
                append(OnDeviceToolCallParser.buildToolDescriptionPrompt(request.tools))
            }
        }
        return ConversationConfig(
            systemInstruction = if (systemInstruction.isNotEmpty()) {
                Contents.of(systemInstruction)
            } else null,
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
