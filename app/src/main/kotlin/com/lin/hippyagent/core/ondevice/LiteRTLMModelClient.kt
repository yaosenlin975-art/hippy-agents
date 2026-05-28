package com.lin.hippyagent.core.ondevice

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelClient
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
    }

    override suspend fun listModels(): List<String> = listOf(modelId)
}
