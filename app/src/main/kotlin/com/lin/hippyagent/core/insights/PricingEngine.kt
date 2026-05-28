package com.lin.hippyagent.core.insights

data class CostBreakdown(
    val inputCost: Double,
    val outputCost: Double,
    val cacheReadCost: Double = 0.0,
    val cacheWriteCost: Double = 0.0
)

data class CostResult(
    val status: String,
    val model: String,
    val costUsd: Double,
    val breakdown: CostBreakdown? = null
)

class PricingEngine {
    private val pricing = mapOf(
        "claude-3.5-sonnet" to ModelPricing(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
        "claude-3-opus" to ModelPricing(input = 15.0, output = 75.0, cacheRead = 1.5, cacheWrite = 18.75),
        "claude-3-haiku" to ModelPricing(input = 0.25, output = 1.25, cacheRead = 0.03, cacheWrite = 0.3),
        "gpt-4o" to ModelPricing(input = 2.5, output = 10.0),
        "gpt-4o-mini" to ModelPricing(input = 0.15, output = 0.6),
        "gpt-4-turbo" to ModelPricing(input = 10.0, output = 30.0),
        "deepseek-chat" to ModelPricing(input = 0.14, output = 0.28, cacheRead = 0.014),
        "deepseek-reasoner" to ModelPricing(input = 0.55, output = 2.19, cacheRead = 0.14),
        "qwen-max" to ModelPricing(input = 1.6, output = 6.4, cacheRead = 0.2),
        "qwen-plus" to ModelPricing(input = 0.4, output = 1.2, cacheRead = 0.05),
        "qwen-turbo" to ModelPricing(input = 0.1, output = 0.3, cacheRead = 0.02),
        "glm-4" to ModelPricing(input = 7.14, output = 7.14),
        "glm-4-flash" to ModelPricing(input = 0.1, output = 0.1),
    )

    fun estimateCost(
        model: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long = 0,
        cacheWriteTokens: Long = 0
    ): CostResult {
        val key = model.lowercase().replace(Regex("[.\\-]"), "")
        val matched = pricing.entries.find { (k, _) ->
            key.contains(k.replace(Regex("[.\\-]"), ""))
        }

        if (matched == null) {
            val fallbackInput = 1.0
            val fallbackOutput = 3.0
            val cost = (inputTokens * fallbackInput + outputTokens * fallbackOutput) / 1_000_000.0
            return CostResult(
                status = "estimated_fallback",
                model = model,
                costUsd = cost,
                breakdown = CostBreakdown(
                    inputCost = inputTokens * fallbackInput / 1_000_000.0,
                    outputCost = outputTokens * fallbackOutput / 1_000_000.0
                )
            )
        }

        val (name, p) = matched
        val inputCost = inputTokens * p.input / 1_000_000.0
        val outputCost = outputTokens * p.output / 1_000_000.0
        val cacheReadCost = cacheReadTokens * (p.cacheRead ?: 0.0) / 1_000_000.0
        val cacheWriteCost = cacheWriteTokens * (p.cacheWrite ?: 0.0) / 1_000_000.0
        val total = inputCost + outputCost + cacheReadCost + cacheWriteCost

        return CostResult(
            status = "estimated",
            model = name,
            costUsd = total,
            breakdown = CostBreakdown(inputCost, outputCost, cacheReadCost, cacheWriteCost)
        )
    }

    data class ModelPricing(
        val input: Double,
        val output: Double,
        val cacheRead: Double? = null,
        val cacheWrite: Double? = null
    )
}

