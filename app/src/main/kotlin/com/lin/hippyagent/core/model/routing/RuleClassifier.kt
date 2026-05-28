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
}

