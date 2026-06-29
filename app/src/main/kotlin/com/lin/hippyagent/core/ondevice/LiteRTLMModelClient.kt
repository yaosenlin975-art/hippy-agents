package com.lin.hippyagent.core.ondevice

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ModelStreamChunk
import kotlinx.coroutines.flow.Flow

class LiteRTLMModelClient(
    private val manager: OnDeviceModelManager,
    private val modelId: String,
) : ModelClient {

    override suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse =
        manager.generate(modelId, request)

    override suspend fun chatCompletionStream(request: ModelCallRequest): Flow<ModelStreamChunk> =
        manager.generateStream(modelId, request)

    override suspend fun testConnection(): Result<Unit> = runCatching {
        check(manager.getEngineState(modelId) == EngineState.LOADED) {
            "端侧模型引擎未加载"
        }
        // 实际推理一次，验证多轮对话修复后引擎可用
        val testRequest = ModelCallRequest(
            model = modelId,
            messages = listOf(
                ModelMessage(role = "user", content = "hi")
            ),
            maxTokens = 16
        )
        manager.generate(modelId, testRequest)
    }

    override suspend fun listModels(): List<String> = listOf(modelId)
}
