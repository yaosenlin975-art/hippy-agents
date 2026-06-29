package com.lin.hippyagent.core.model

import timber.log.Timber

/**
 * 上下文窗口硬下限保护器。
 *
 * 3 级优先级解析：modelsConfig.contextWindow → modelMetadata.contextWindow → 默认 128k
 * shouldWarn(<32k) / shouldBlock(<16k) 双门槛
 *
 * 设计依据：spec T0-2，参考 X-OmniClaw agent/context/ContextWindowGuard.kt
 */
object ContextWindowGuard {

    const val DEFAULT_CONTEXT_WINDOW = 128_000
    const val WARN_THRESHOLD = 32_000
    const val BLOCK_THRESHOLD = 16_000

    enum class GuardDecision { OK, WARN, BLOCK }

    enum class Source { MODELS_CONFIG, MODEL_METADATA, DEFAULT }

    data class GuardResult(
        val decision: GuardDecision,
        val effectiveContextWindow: Int,
        val source: Source,
        val message: String? = null
    )

    /**
     * 校验上下文窗口配置。
     *
     * @param contextWindowFromConfig 用户在 ModelConfig 显式配置的 contextWindow
     * @param contextWindowFromMetadata 模型元数据中的 contextWindow（本期预留，默认 null）
     */
    fun check(
        contextWindowFromConfig: Int?,
        contextWindowFromMetadata: Int? = null
    ): GuardResult {
        val (window, source) = when {
            contextWindowFromConfig != null && contextWindowFromConfig > 0 ->
                contextWindowFromConfig to Source.MODELS_CONFIG
            contextWindowFromMetadata != null && contextWindowFromMetadata > 0 ->
                contextWindowFromMetadata to Source.MODEL_METADATA
            else -> DEFAULT_CONTEXT_WINDOW to Source.DEFAULT
        }

        return when {
            window < BLOCK_THRESHOLD -> GuardResult(
                decision = GuardDecision.BLOCK,
                effectiveContextWindow = window,
                source = source,
                message = "上下文窗口 $window < $BLOCK_THRESHOLD，" +
                    "不足以支撑 Agent 多轮工具调用，请配置更大窗口的模型或调高 contextWindow"
            )
            window < WARN_THRESHOLD -> GuardResult(
                decision = GuardDecision.WARN,
                effectiveContextWindow = window,
                source = source,
                message = "上下文窗口 $window < $WARN_THRESHOLD，Agent 可能频繁触发上下文压缩"
            )
            else -> GuardResult(
                decision = GuardDecision.OK,
                effectiveContextWindow = window,
                source = source
            )
        }
    }

    /** 运行时告警：不阻断，仅记录日志 */
    fun warnIfNecessary(result: GuardResult, modelId: String) {
        when (result.decision) {
            GuardDecision.WARN -> Timber.w("ContextWindow WARN: model=$modelId window=${result.effectiveContextWindow}")
            GuardDecision.BLOCK -> Timber.e("ContextWindow BLOCK: model=$modelId window=${result.effectiveContextWindow}")
            GuardDecision.OK -> { /* no-op */ }
        }
    }
}
