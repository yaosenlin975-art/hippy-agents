package com.lin.hippyagent.core.model.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * Result of a model routing decision.
 */
@Serializable
data class RoutingDecision(
    /** The selected model identifier. */
    @SerialName("selected_model")
    val selectedModel: String,

    /** Whether the light model was selected. */
    @SerialName("used_light_model")
    val usedLightModel: Boolean,

    /** The composite complexity score that drove the decision. */
    @SerialName("score")
    val score: Float,

    /** Human-readable reasons for the decision. */
    @SerialName("reasons")
    val reasons: List<String>,

    /** Whether the fallback model was used due to provider failure. */
    @SerialName("used_fallback")
    val usedFallback: Boolean = false
)

/**
 * Smart model router that selects between a light and heavy model based
 * on message complexity analysis.
 *
 * The routing flow:
 * 1. Extract six-dimensional features from the message.
 * 2. Classify using weighted scoring rules.
 * 3. Select light or heavy model accordingly.
 * 4. If the selected model's provider is unavailable, fall back to the
 *    fallback model (if configured).
 *
 * @param classifier  The rule-based classifier for complexity scoring.
 * @param providerRegistry Optional registry to check model availability
 *                         before making a routing decision.
 */
class ModelRouter(
    private val classifier: RuleClassifier = RuleClassifier(),
    private val providerRegistry: ProviderRegistry? = null,
    private val llmTaskRouter: LlmTaskRouter? = null,
    private val onDeviceModelManager: com.lin.hippyagent.core.ondevice.OnDeviceModelManager? = null
) {

    /**
     * Select the best model for the given message and routing configuration.
     *
     * @param message The user message to classify.
     * @param config  Routing configuration specifying light, heavy, and fallback models.
     * @param toolCallCount Optional tool call count for agentic contexts.
     * @return A [RoutingDecision] with the selected model and reasoning.
     */
    fun selectModel(
        message: String,
        config: RoutingConfig,
        toolCallCount: Int = 0,
        historyTokenEstimate: Int = 0,
        hasMultimodal: Boolean = false
    ): RoutingDecision {
        val complexity = MessageComplexityExtractor.extract(message, toolCallCount, historyTokenEstimate, hasMultimodal)

        // Step 2: Classify
        val classification = classifier.classify(complexity)

        Timber.d(
            "ModelRouter: score=${classification.score}, level=${classification.level}, " +
                "reasons=${classification.reasons}"
        )

        // Step 3: Select model based on classification
        val primaryModel = when (classification.level) {
            ComplexityLevel.LIGHT -> config.lightModel
            ComplexityLevel.HEAVY -> config.heavyModel
        }

        val usedLight = classification.level == ComplexityLevel.LIGHT

        // Step 4: Check provider availability and apply fallback if needed
        val selectedModel: String
        var usedFallback = false

        if (providerRegistry != null && !providerRegistry.isModelAvailable(primaryModel)) {
            val fallback = config.fallbackModel
            if (fallback != null && providerRegistry.isModelAvailable(fallback)) {
                Timber.w(
                    "ModelRouter: primary model '$primaryModel' unavailable, " +
                        "falling back to '$fallback'"
                )
                selectedModel = fallback
                usedFallback = true
            } else {
                // No viable fallback; use primary anyway (let the caller handle errors)
                Timber.w(
                    "ModelRouter: primary model '$primaryModel' unavailable " +
                        "and no fallback configured"
                )
                selectedModel = primaryModel
            }
        } else {
            selectedModel = primaryModel
        }

        val reasons = classification.reasons.toMutableList()
        if (usedFallback) {
            reasons.add("使用 fallback 模型: $selectedModel")
        }

        return RoutingDecision(
            selectedModel = selectedModel,
            usedLightModel = usedLight,
            score = classification.score,
            reasons = reasons,
            usedFallback = usedFallback
        )
    }

    /**
     * B2 混合路由：规则层 + LLM 层 + 端侧路由。
     *
     * 1. 规则层先判断 → ONDEVICE/CLOUD/UNCERTAIN
     * 2. UNCERTAIN 时，若 LlmTaskRouter 可用，交给 LLM 层
     * 3. LLM 层失败或不可用，默认 CLOUD（安全）
     *
     * @param message 用户消息
     * @param config 路由配置（含 onDeviceModel）
     * @param toolCallCount 工具调用计数
     * @param historyTokenEstimate 历史 token 估算
     * @param hasMultimodal 是否含多模态内容
     * @return 路由决策
     */
    suspend fun selectModelWithOnDevice(
        message: String,
        config: RoutingConfig,
        toolCallCount: Int = 0,
        historyTokenEstimate: Int = 0,
        hasMultimodal: Boolean = false,
        hasTools: Boolean = false
    ): RoutingDecision {
        // 若未配置端侧模型，降级到原有 selectModel
        if (config.onDeviceModel == null) {
            return selectModel(message, config, toolCallCount, historyTokenEstimate, hasMultimodal)
        }

        val complexity = MessageComplexityExtractor.extract(message, toolCallCount, historyTokenEstimate, hasMultimodal)
        val routeDecision = classifier.classifyRoute(complexity)

        val target = when (routeDecision.target) {
            RouteTarget.ONDEVICE -> RouteTarget.ONDEVICE
            RouteTarget.CLOUD -> RouteTarget.CLOUD
            RouteTarget.UNCERTAIN -> {
                // LLM 层判断
                if (llmTaskRouter != null) {
                    val llmDecision = llmTaskRouter.route(message, hasTools)
                    llmDecision.target
                } else {
                    RouteTarget.CLOUD
                }
            }
        }

        return when (target) {
            RouteTarget.ONDEVICE -> {
                val onDeviceId = config.onDeviceModel
                if (onDeviceId != null && isOnDeviceModelReady(onDeviceId)) {
                    RoutingDecision(
                        selectedModel = onDeviceId,
                        usedLightModel = true,
                        score = routeDecision.confidence,
                        reasons = listOf("B2 端侧路由: ${routeDecision.reason}"),
                        usedFallback = false
                    )
                } else {
                    // 端侧不可用，降级到云端 light
                    RoutingDecision(
                        selectedModel = config.lightModel,
                        usedLightModel = true,
                        score = routeDecision.confidence,
                        reasons = listOf("B2 端侧不可用，降级云端 light: ${routeDecision.reason}"),
                        usedFallback = true
                    )
                }
            }
            RouteTarget.CLOUD -> {
                // 云端内部再走 light/heavy 路由（复用原有逻辑）
                selectModel(message, config, toolCallCount, historyTokenEstimate, hasMultimodal)
            }
            RouteTarget.UNCERTAIN -> {
                // 不会到这里（已在上面处理）
                selectModel(message, config, toolCallCount, historyTokenEstimate, hasMultimodal)
            }
        }
    }

    private fun isOnDeviceModelReady(modelId: String): Boolean {
        return onDeviceModelManager?.getEngineState(modelId) == com.lin.hippyagent.core.ondevice.EngineState.LOADED
    }
}

/**
 * Minimal interface for checking whether a model is reachable.
 * Implementations can query provider health endpoints or maintain
 * an in-memory availability cache.
 */
interface ProviderRegistry {
    fun isModelAvailable(modelId: String): Boolean
}

