package com.lin.hippyagent.core.model.routing

/**
 * Binary complexity level for model routing decisions.
 */
enum class ComplexityLevel {
    /** Simple request: short text, no code, few technical terms. Use the light model. */
    LIGHT,

    /** Complex request: code-heavy, technical, long, or multi-turn. Use the heavy model. */
    HEAVY
}

/**
 * 端侧/云端三分类路由目标。
 */
enum class RouteTarget {
    /** 端侧模型可处理 */
    ONDEVICE,
    /** 必须走云端 */
    CLOUD,
    /** 不确定，交给 LLM 路由器判断 */
    UNCERTAIN
}

/**
 * 三分类路由决策结果。
 */
data class RouteDecision(
    val target: RouteTarget,
    val confidence: Float,
    val reason: String
)

/**
 * Classification result containing the level, score, and human-readable reasons.
 */
data class ClassificationResult(
    val level: ComplexityLevel,
    val score: Float,
    val reasons: List<String>
)

/**
 * Weighted rule-based classifier that maps [MessageComplexity] to a
 * binary [ComplexityLevel].
 *
 * The classifier uses the [MessageComplexity.compositeScore] as its primary
 * signal, but can also apply per-dimension overrides for edge cases
 * (e.g., a message with many code blocks should always be HEAVY regardless
 * of the composite score).
 */
class RuleClassifier(
    /**
     * Score threshold: messages scoring below this are LIGHT, at or above are HEAVY.
     */
    private val threshold: Float = 0.35f
) {

    /**
     * Classify a [MessageComplexity] into a [ComplexityLevel].
     *
     * @param complexity The extracted message features.
     * @return A [ClassificationResult] with the level, raw score, and reasons.
     */
    fun classify(complexity: MessageComplexity): ClassificationResult {
        val score = complexity.compositeScore
        val reasons = mutableListOf<String>()

        // Per-dimension override checks
        var forcedHeavy = false

        if (complexity.codeBlockCount >= 3) {
            reasons.add("代码块数量多 (${complexity.codeBlockCount} >= 3)")
            forcedHeavy = true
        }

        if (complexity.technicalTermCount >= 8) {
            reasons.add("技术术语密集 (${complexity.technicalTermCount} >= 8)")
            forcedHeavy = true
        }

        if (complexity.cjkTokenEstimate >= 300) {
            reasons.add("大量CJK文本 (${complexity.cjkTokenEstimate} tokens)")
            forcedHeavy = true
        }

        if (complexity.historyContextSize >= 6000) {
            reasons.add("上下文过长 (${complexity.historyContextSize} tokens)")
            forcedHeavy = true
        }

        if (complexity.hasMultimodal) {
            reasons.add("包含多模态内容")
            forcedHeavy = true
        }

        val level = if (forcedHeavy || score >= threshold) {
            ComplexityLevel.HEAVY
        } else {
            ComplexityLevel.LIGHT
        }

        if (reasons.isEmpty()) {
            reasons.add(
                "综合评分 ${"%.3f".format(score)} ${if (level == ComplexityLevel.HEAVY) ">=" else "<"} 阈值 $threshold"
            )
        }

        return ClassificationResult(
            level = level,
            score = score,
            reasons = reasons
        )
    }

    /**
     * 三分类路由：返回端侧/云端/不确定。
     * 用于 B2 混合路由，扩展原有二分类（LIGHT/HEAVY）为三分类（ONDEVICE/CLOUD/UNCERTAIN）。
     *
     * @param complexity 消息特征
     * @return 路由决策
     */
    fun classifyRoute(complexity: MessageComplexity): RouteDecision {
        // 1. 强制云端规则（端侧无法处理的场景）优先
        if (complexity.codeBlockCount >= 1) {
            return RouteDecision(RouteTarget.CLOUD, 1.0f, "FORCED_CLOUD: 含代码块(${complexity.codeBlockCount})")
        }
        if (complexity.technicalTermCount >= 5) {
            return RouteDecision(RouteTarget.CLOUD, 1.0f, "FORCED_CLOUD: 技术术语密集(${complexity.technicalTermCount})")
        }
        if (complexity.hasMultimodal) {
            return RouteDecision(RouteTarget.CLOUD, 1.0f, "FORCED_CLOUD: 多模态输入")
        }
        if (complexity.cjkTokenEstimate > 500) {
            return RouteDecision(RouteTarget.CLOUD, 1.0f, "FORCED_CLOUD: 超长消息(${complexity.cjkTokenEstimate} tokens)")
        }

        // 2. 强制端侧规则（简单任务）
        if (complexity.codeBlockCount == 0 && complexity.toolCallDensity == 0f && complexity.cjkTokenEstimate < 100) {
            return RouteDecision(RouteTarget.ONDEVICE, 0.9f, "FORCED_ONDEVICE: 无代码+无工具+短消息")
        }
        if (complexity.cjkTokenEstimate < 20 && complexity.questionCount <= 0) {
            return RouteDecision(RouteTarget.ONDEVICE, 0.9f, "FORCED_ONDEVICE: 纯寒暄")
        }
        if (complexity.technicalTermCount == 0 && complexity.cjkTokenEstimate < 150 && complexity.codeBlockCount == 0) {
            return RouteDecision(RouteTarget.ONDEVICE, 0.9f, "FORCED_ONDEVICE: 简单问答")
        }

        // 3. 不确定 → 交给 LLM 路由器
        return RouteDecision(RouteTarget.UNCERTAIN, 0.5f, "NEEDS_LLM_ROUTER")
    }
}

