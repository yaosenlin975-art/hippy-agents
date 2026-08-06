package com.lin.hippyagent.core.agent

import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.memory.compaction.IterativeSummaryMerger
import com.lin.hippyagent.core.model.AuthProfileManager
import com.lin.hippyagent.core.model.FailoverAction
import com.lin.hippyagent.core.model.FailoverEngine
import com.lin.hippyagent.core.model.FailoverError
import com.lin.hippyagent.core.model.LlmRateLimitException
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanContext
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceContextElement
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.delay
import timber.log.Timber

internal data class RoutedModelResult(val modelName: String, val client: ModelClient)

/**
     * 调用 LLM 执行真正的上下文压缩。
     * 如果调用失败，回退到规则摘要（IterativeSummaryMerger）。
     */
internal suspend fun Agent.performLlmCompaction(
        compactionPrompt: String,
        existingSummary: String?,
        messagesToCompress: List<com.lin.hippyagent.core.agent.session.SessionMessage>
    ): String {
        val traceCtx = coroutineContext[TraceContextElement]
        val span = if (traceCtx != null) {
            SpanCollector.startSpan(
                type = SpanType.CONTEXT_COMPACTION,
                traceId = traceCtx.traceId,
                parentSpanId = traceCtx.parentSpanId,
                props = mapOf(
                    "beforeTokens" to messagesToCompress.sumOf { it.content.toByteArray(Charsets.UTF_8).size / 4 },
                    "strategy" to "summarize"
                )
            )
        } else {
            SpanContext.NoOp
        }
        try {
            val result = try {
                // B4：摘要优先用 summaryModel（端侧），失败降级到主模型
                val summaryModelName = profile.summaryModelName.takeIf { it.isNotEmpty() }
                    ?: profile.modelName
                val summaryModelProvider = profile.summaryModelProvider.takeIf { it.isNotEmpty() }
                    ?: profile.modelProvider

                val request = ModelCallRequest(
                    model = stripModelPrefix(summaryModelName),
                    messages = listOf(
                        ModelMessage(role = "system", content = Agent.COMPACT_SYSTEM_PROMPT),
                        ModelMessage(role = "user", content = compactionPrompt)
                    ),
                    temperature = 0.3f,
                    maxTokens = 2048
                )

                // B4：若 summaryModelProvider 是端侧，先确保引擎加载
                if (summaryModelProvider.startsWith("ondevice-")) {
                    onDeviceModelManager?.ensureEngineLoaded(summaryModelName)
                }
                val summaryClient = resolveModelClient(summaryModelProvider)

                val resp = try {
                    callLlmWithRetryAndRateLimit(request, summaryClient)
                } catch (e: Exception) {
                    // B4：summaryModel 失败，降级到主模型
                    if (summaryModelName != profile.modelName || summaryModelProvider != profile.modelProvider) {
                        Timber.w(e, "Summary model failed, falling back to primary model")
                        callLlmWithRetryAndRateLimit(
                            request.copy(model = stripModelPrefix(profile.modelName)),
                            modelClient
                        )
                    } else {
                        throw e
                    }
                }
                val summary = resp.choices.firstOrNull()?.message?.content
                    ?: throw IllegalStateException("Compression LLM returned no content")

                if (existingSummary != null) {
                    summaryMerger.mergeWithNewSummary(existingSummary, summary)
                } else {
                    summary
                }
            } catch (e: Exception) {
                Timber.w(e, "LLM compaction failed, falling back to rule-based summary")
                var summary = if (existingSummary != null) {
                    summaryMerger.merge(existingSummary, messagesToCompress)
                } else {
                    summaryMerger.merge("", messagesToCompress)
                }

                val fallbackConfig = profile.running.lightContextConfig.contextCompactConfig
                if (fallbackConfig.compactionFallbackEnabled) {
                    val contextWindow = resolveModelContextWindow() ?: profile.running.maxInputLength
                    val summaryTokens = summary.toByteArray(Charsets.UTF_8).size / 4
                    val maxSummaryTokens = (contextWindow * (1f - fallbackConfig.compactionFallbackReserveRatio)).toInt()

                    if (summaryTokens > maxSummaryTokens) {
                        Timber.w("Rule-based summary still over limit ($summaryTokens > $maxSummaryTokens), re-splitting with fallback ratio ${fallbackConfig.compactionFallbackReserveRatio}")
                        val fallbackReserve = (contextWindow * fallbackConfig.compactionFallbackReserveRatio).toInt()
                        var keepCount = 0
                        var keepTokens = 0
                        for (i in messagesToCompress.indices.reversed()) {
                            val msgTokens = messagesToCompress[i].content.toByteArray(Charsets.UTF_8).size / 4 + 4
                            if (keepTokens + msgTokens > fallbackReserve) break
                            keepCount++
                            keepTokens += msgTokens
                        }
                        if (keepCount < messagesToCompress.size) {
                            val toCompress = messagesToCompress.dropLast(keepCount)
                            summary = if (existingSummary != null) {
                                summaryMerger.merge(existingSummary, toCompress)
                            } else {
                                summaryMerger.merge("", toCompress)
                            }
                            Timber.w("Fallback re-split: compressed ${toCompress.size} messages, promoted $keepCount recent messages")
                        }
                    }
                }

                summary
            }
            SpanCollector.end(span, extraProps = mapOf(
                "afterTokens" to (result.toByteArray(Charsets.UTF_8).size / 4)
            ))
            return result
        } catch (e: Exception) {
            SpanCollector.end(span, error = e.message)
            throw e
        }
    }

internal suspend fun Agent.resolveRoutedModel(
        sessionId: String,
        content: String,
        overrideModel: String?,
        escalatedThisTurn: Boolean,
        effectiveClient: ModelClient,
        isStream: Boolean = false,
        hasTools: Boolean = false
    ): RoutedModelResult {
        val tag = if (isStream) "(stream)" else ""
        val routedModel = if (overrideModel == null && modelRouter != null) {
            if (escalatedThisTurn && profile.complexModelName.isNotEmpty()) {
                Timber.d("ModelRouter$tag: ESCALATED → using HEAVY model ${profile.complexModelName}")
                stripModelPrefix(profile.complexModelName)
            } else {
                val historyTokens = tokenUsageState.value.totalTokens.toInt()
                val sessionSt = _state.value.getSessionState(sessionId)
                val routingConfig = com.lin.hippyagent.core.model.routing.RoutingConfig(
                    lightModel = profile.modelName,
                    heavyModel = if (profile.complexModelName.isNotEmpty()) profile.complexModelName else profile.modelName,
                    onDeviceModel = profile.fallbackModelName.takeIf {
                        it.isNotEmpty() && profile.fallbackModelProvider.startsWith("ondevice-")
                    }
                )
                val routing = modelRouter.selectModelWithOnDevice(
                    message = content,
                    config = routingConfig,
                    toolCallCount = sessionSt.toolCallCount,
                    historyTokenEstimate = historyTokens,
                    hasTools = hasTools
                )
                if (routing.usedLightModel) {
                    Timber.d("ModelRouter$tag: using LIGHT model ${routing.selectedModel} (score=${routing.score})")
                } else {
                    Timber.d("ModelRouter$tag: using HEAVY model ${routing.selectedModel} (score=${routing.score})")
                }
                stripModelPrefix(routing.selectedModel)
            }
        } else {
            stripModelPrefix(overrideModel ?: profile.modelName)
        }

        var routingClient = effectiveClient
        if (overrideModel == null && modelRouter != null && profile.complexModelName.isNotEmpty()
            && routedModel == stripModelPrefix(profile.complexModelName)
            && profile.complexModelProvider.isNotEmpty()
        ) {
            val complexClient = resolveModelClient(profile.complexModelProvider)
            if (complexClient !== effectiveClient) {
                Timber.d("ModelRouter$tag: switching client to complexModelProvider=${profile.complexModelProvider}")
                routingClient = complexClient
            }
        } else if (overrideModel == null && modelRouter != null
            && profile.fallbackModelProvider.startsWith("ondevice-")
            && profile.fallbackModelName.isNotEmpty()
            && routedModel == stripModelPrefix(profile.fallbackModelName)
        ) {
            val onDeviceClient = resolveModelClient(profile.fallbackModelProvider)
            if (onDeviceClient !== effectiveClient) {
                Timber.d("ModelRouter$tag: switching client to onDevice provider=${profile.fallbackModelProvider}")
                routingClient = onDeviceClient
            }
        }

        return RoutedModelResult(routedModel, routingClient)
    }

internal fun Agent.shouldAutoContinue(
        content: String?,
        autoContinueExtraCount: Int
    ): Boolean {
        return profile.running.autoContinueOnTextOnly && !content.isNullOrBlank() && autoContinueExtraCount < AUTO_CONTINUE_MAX_EXTRA
    }

internal fun Agent.autoContinueSystemHint(): String {
        val lang = profile.running.agentLanguage.trim().lowercase()
        return if (lang == "zh") AUTO_CONTINUE_HINT_ZH else AUTO_CONTINUE_HINT_EN
    }

internal fun Agent.autoContinueTailContext(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= AUTO_CONTINUE_TAIL_CHARS) return trimmed
        return trimmed.takeLast(AUTO_CONTINUE_TAIL_CHARS).trimStart()
    }

internal suspend fun Agent.callLlmWithRetryAndRateLimit(request: ModelCallRequest, client: ModelClient = modelClient): ModelCallResponse {
        val cfg = profile.running
        if (!cfg.llmRetryEnabled) {
            return rateLimiter.acquireAndExecute { client.chatCompletion(request) }
        }

        // P0-3: 如果 FailoverEngine 可用，走故障转移流程
        if (failoverEngine != null) {
            return executeWithFailover(request, client)
        }

        // 原始简单重试逻辑（作为后备）
        var lastException: Throwable? = null
        for (attempt in 0..cfg.llmRetryMaxRetries) {
            try {
                return rateLimiter.acquireAndExecute { client.chatCompletion(request) }
            } catch (e: LlmRateLimitException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                    throw e
                }
                if (e.message?.contains("429") == true) {
                    rateLimiter.notify429()
                    continue
                }
                if (attempt < cfg.llmRetryMaxRetries) {
                    val baseMs = (cfg.llmRetryBackoffBase * 1000).toLong()
                    val capMs = (cfg.llmRetryBackoffCap * 1000).toLong()
                    val delayMs = min(
                        (baseMs * 2.0.pow(attempt.toDouble())).toLong(),
                        capMs
                    )
                    val jitter = if (delayMs > 100) Random.nextLong(0, delayMs / 4) else 0
                    Timber.w("LLM call failed (attempt ${attempt + 1}/${cfg.llmRetryMaxRetries}), retrying in ${delayMs + jitter}ms: ${e.message}")
                    delay(delayMs + jitter)
                }
            }
        }
        // 主模型重试耗尽，尝试 profile 配置的 fallback 模型
        if (profile.fallbackModelName.isNotEmpty()) {
            Timber.w("Primary model exhausted (simple retry), trying fallback: ${profile.fallbackModelName}")
            val fallbackClient = resolveModelClient(
                profile.fallbackModelProvider.takeIf { it.isNotEmpty() && it != profile.modelProvider }
            )
            try {
                val result = rateLimiter.acquireAndExecute { fallbackClient.chatCompletion(request.copy(model = stripModelPrefix(profile.fallbackModelName))) }
                // 标记当前会话使用了 fallback 模型
                currentSessionId()?.let { sid ->
                    updateSessionState(sid) { it.copy(usedFallbackModel = profile.fallbackModelName) }
                }
                return result
            } catch (e: Exception) {
                Timber.e(e, "Fallback model also failed (simple retry): ${profile.fallbackModelName}")
            }
        }
        throw lastException ?: RuntimeException("All model retries exhausted")
    }

/**
     * P0-3: 使用 FailoverEngine 执行 LLM 调用，支持 Profile 轮换和智能重试。
     */
internal suspend fun Agent.executeWithFailover(request: ModelCallRequest, client: ModelClient = modelClient): ModelCallResponse {
        var currentRequest = request
        var retryCount = 0
        val maxRetries = profile.running.llmRetryMaxRetries

        while (retryCount <= maxRetries) {
            try {
                val result = rateLimiter.acquireAndExecute { client.chatCompletion(currentRequest) }
                failoverEngine?.resetRetryCount()
                return result
            } catch (e: LlmRateLimitException) {
                throw e // 由 rateLimiter 自行处理
            } catch (e: Exception) {
                val failoverError = failoverEngine?.classifyError(e) ?: throw e
                val decision = failoverEngine.decide(failoverError, currentRetry = 0)

                Timber.w("Failover: ${decision.action} - ${decision.reason}")

                when (decision.action) {
                    FailoverAction.GIVE_UP -> throw failoverError
                    FailoverAction.SURFACE_TO_USER -> throw failoverError
                    FailoverAction.COMPRESS_CONTEXT -> throw failoverError
                    FailoverAction.ROTATE_PROFILE -> {
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        // Profile 轮换由 ModelClient 层面处理（通过 AuthProfileManager 的 cooldown）
                        retryCount++
                    }
                    FailoverAction.SWITCH_MODEL -> {
                        if (decision.nextModel != null) {
                            currentRequest = currentRequest.copy(model = stripModelPrefix(decision.nextModel))
                        }
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        retryCount++
                    }
                    FailoverAction.SWITCH_PROVIDER -> {
                        // Provider 切换需要重建 ModelClient，当前暂由外层处理
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        retryCount++
                    }
                    FailoverAction.RETRY_SAME -> {
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        retryCount++
                    }
                }
            }
        }

        // 主模型重试耗尽，尝试 profile 配置的 fallback 模型
        if (profile.fallbackModelName.isNotEmpty()) {
            Timber.w("Primary model exhausted, trying fallback: ${profile.fallbackModelName}")
            val fallbackClient = resolveModelClient(
                profile.fallbackModelProvider.takeIf { it.isNotEmpty() && it != profile.modelProvider }
            )
            val fallbackRequest = currentRequest.copy(model = stripModelPrefix(profile.fallbackModelName))
            try {
                val result = rateLimiter.acquireAndExecute { fallbackClient.chatCompletion(fallbackRequest) }
                failoverEngine?.resetRetryCount()
                // 标记当前会话使用了 fallback 模型
                currentSessionId()?.let { sid ->
                    updateSessionState(sid) { it.copy(usedFallbackModel = profile.fallbackModelName) }
                }
                return result
            } catch (e: Exception) {
                Timber.e(e, "Fallback model also failed: ${profile.fallbackModelName}")
            }
        }

        throw FailoverError("达到最大重试次数", com.lin.hippyagent.core.model.FailoverReason.UNKNOWN)
    }

internal val AUTO_CONTINUE_MAX_EXTRA = 2

internal val AUTO_CONTINUE_TAIL_CHARS = 600

internal val AUTO_CONTINUE_HINT_ZH = """
<system-hint>你上一轮的回复只包含纯文本，没有使用任何工具。
请根据<previous-assistant-tail>中的结尾内容（若有）在本轮推理中判断：仍需执行则立刻 tool；已完结则简短收尾。
需要操作时勿只输出计划或代码块。</system-hint>
""".trimIndent()

internal val AUTO_CONTINUE_HINT_EN = """
<system-hint>Your previous assistant turn had text only (no tool calls).
Use the trailing excerpt in <previous-assistant-tail> (if present) plus the conversation to decide in this **reasoning** step: if the user's task still needs tools, emit tool_use now; if it is fully done, reply with a short text only (no tools).
Do not stop with plans or code fences alone when tools are still needed.</system-hint>
""".trimIndent()
