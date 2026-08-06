package com.lin.hippyagent.core.agent

import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.channel.ChannelMessage
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.security.InputGuard
import com.lin.hippyagent.core.security.RiskLevel
import com.lin.hippyagent.core.security.SecuritySpanReporter
import com.lin.hippyagent.core.security.injection.InjectionDetector
import com.lin.hippyagent.core.security.output.OutputValidator
import com.lin.hippyagent.core.security.pii.PiiMasker
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceContextElement
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

internal suspend fun Agent.processMessage(
        sessionId: String,
        channelId: String,
        content: String,
        overrideModel: String? = null,
        skipUserMessage: Boolean = false,
        overrideProviderId: String? = null,
        systemPromptSuffix: String? = null,
        forceEscalate: Boolean = false,
    ): Result<Unit> {
        val traceId = UUID.randomUUID().toString()
        val traceSpan = SpanCollector.startSpan(
            type = SpanType.AGENT_LOOP,
            traceId = traceId,
            parentSpanId = null,
            props = mapOf(
                "agentId" to profile.agentId,
                "modelId" to (overrideModel ?: profile.modelName),
                "userMessage" to content.take(200),
                "iterationCount" to 0
            )
        )
        return withContext(TraceContextElement(traceId, null)) {
            doProcessMessage(
                sessionId, channelId, content, overrideModel, skipUserMessage, overrideProviderId, systemPromptSuffix, forceEscalate
            )
        }.also { result ->
            SpanCollector.end(traceSpan, error = if (result.isFailure) result.exceptionOrNull()?.message else null)
        }
    }

internal suspend fun Agent.doProcessMessage(
        sessionId: String,
        channelId: String,
        content: String,
        overrideModel: String? = null,
        skipUserMessage: Boolean = false,
        overrideProviderId: String? = null,
        systemPromptSuffix: String? = null,
        forceEscalate: Boolean = false,
    ): Result<Unit> = getOrCreateSessionMutex(sessionId).withLock {
    getOrCreateSessionContext(sessionId).job = requireNotNull(coroutineContext[Job]) { "Agent loop requires a coroutine Job in context" }
    _currentProcessingSessionId = sessionId
        sessionManager?.createSession(sessionId, profile.agentId, channelId)
        sessionManager?.updateActivity(sessionId)
        updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING, isThinking = true, usedFallbackModel = null) }

        val traceId = coroutineContext[TraceContextElement]?.traceId ?: UUID.randomUUID().toString()
        val piiMasker = PiiMasker()
        val inputGuard = InputGuard(piiMasker, traceId)
        val outputValidator = OutputValidator(piiMasker)
        var consecutiveValidationFailures = 0

        var capturedMessages: MutableList<ModelMessage>? = null
        return runCatching {
            Timber.d("Agent ${profile.agentId} processing message: $content")

            // B3：隐私模式检查 — 强制走端侧模型，数据不出设备
            val session = sessionStore.getSession(sessionId).getOrNull()
            if (session?.privacyMode == true) {
                return@runCatching runLoopOnDeviceOnly(
                    sessionId, channelId, content, skipUserMessage, systemPromptSuffix
                ).getOrThrow()
            }

            val guardedInput = inputGuard.guard(content, InjectionDetector.DetectionResult.Source.USER_INPUT)
            for (detection in guardedInput.detections) {
                SecuritySpanReporter.report(
                    type = when (detection.type) {
                        InputGuard.Detection.DetectionType.PII_MASKED -> SecuritySpanReporter.SecurityEventType.PII_MASKED
                        InputGuard.Detection.DetectionType.INJECTION_DETECTED -> SecuritySpanReporter.SecurityEventType.INJECTION_DETECTED
                        InputGuard.Detection.DetectionType.JAILBREAK_DETECTED -> SecuritySpanReporter.SecurityEventType.JAILBREAK_DETECTED
                    },
                    severity = detection.severity,
                    traceId = traceId,
                    source = detection.source.name,
                    ruleId = detection.ruleId,
                    matchedSnippet = detection.matchedSnippet,
                    blocked = detection.blocked
                )
            }
            val guardedContent = guardedInput.processedText

            val ctx = prepareMessageContext(sessionId, channelId, guardedContent, overrideProviderId, skipUserMessage, systemPromptSuffix, overrideModel, forceEscalate)
                ?: return Result.failure(NetworkUnavailableException("网络连接不可用，消息已缓存"))

            val effectiveClient = ctx.effectiveClient
            val messages = ctx.messages
            capturedMessages = messages
            val toolDefinitions = ctx.toolDefinitions
            var escalatedThisTurn = ctx.escalatedThisTurn

            val loopDetector = com.lin.hippyagent.core.agent.loop.ToolLoopDetection().also { detector ->
                // 注册默认轮询类工具
                com.lin.hippyagent.core.agent.loop.ToolLoopDetection.DEFAULT_POLL_TOOLS.forEach {
                    detector.registerPollTool(it)
                }
            }
            var autoContinueExtraCount = 0
            val turnFailureTracker = com.lin.hippyagent.core.model.routing.TurnFailureTracker()

            repeat(profile.running.maxIters) { iteration ->
                if (isSessionInterrupted(sessionId)) {
                    Timber.d("Agent ${profile.agentId} session $sessionId interrupted at iteration $iteration")
                    clearSessionInterrupt(sessionId)
                    return@runCatching
                }

                val isLastIteration = iteration >= profile.running.maxIters - 1
                if (isLastIteration) {
                    messages.add(ModelMessage(
                        role = "system",
                        content = "⚠️ 注意：这是你本次任务的最后一次迭代机会。请立即总结你目前的工作成果和进度，包括已完成的部分和尚未完成的部分。不要继续调用工具，直接给出总结。"
                    ))
                }
                var effectiveMessages = runBeforeModel(sessionId, messages, iteration)

                val routed = resolveRoutedModel(sessionId, content, overrideModel, escalatedThisTurn, effectiveClient, hasTools = toolDefinitions.isNotEmpty())
                val routedModel = routed.modelName
                val routingClient = routed.client

                val request = ModelCallRequest(
                    model = routedModel,
                    messages = effectiveMessages,
                    temperature = 0.7f,
                    maxTokens = resolveModelMaxTokens() ?: profile.running.maxOutputTokens,
                    tools = toolDefinitions
                )

                val resp = callLlmWithRetryAndRateLimit(request, routingClient)

                resp.usage?.let { usage ->
                    _tokenUsage.update { tu ->
                        tu.copy(
                            inputTokens = tu.inputTokens + usage.promptTokens.toLong(),
                            outputTokens = tu.outputTokens + usage.completionTokens.toLong(),
                            totalTokens = tu.totalTokens + usage.totalTokens.toLong(),
                            apiCalls = tu.apiCalls + 1,
                            cacheReadTokens = tu.cacheReadTokens + usage.cacheReadTokens.toLong(),
                            cacheWriteTokens = tu.cacheWriteTokens + usage.cacheWriteTokens.toLong()
                        )
                    }
                    tokenUsageManager?.recordUsage(
                        providerId = profile.modelProvider,
                        modelName = request.model,
                        inputTokens = usage.promptTokens,
                        outputTokens = usage.completionTokens,
                        agentId = profile.agentId,
                        cacheReadTokens = usage.cacheReadTokens,
                        cacheWriteTokens = usage.cacheWriteTokens
                    )
                }

                val registeredToolNames = toolDefinitions.map { it.name }.toSet()
                val validationResult = outputValidator.validate(resp, registeredToolNames)
                if (!validationResult.valid) {
                    consecutiveValidationFailures++
                    SecuritySpanReporter.report(
                        type = SecuritySpanReporter.SecurityEventType.OUTPUT_VALIDATION_FAILED,
                        severity = RiskLevel.HIGH,
                        traceId = traceId,
                        source = "LLM_OUTPUT",
                        blocked = true,
                        ruleId = validationResult.errors.joinToString(";")
                    )
                    if (consecutiveValidationFailures >= 3) {
                        throw RuntimeException("连续 3 次 LLM 输出校验失败，终止")
                    }
                    return@repeat
                }
                consecutiveValidationFailures = 0

                val choice = resp.choices.firstOrNull()
                    ?: throw IllegalStateException("No choices in response")

                val nonStreamToolArgs = choice.message.toolCalls?.joinToString("|") { it.function.arguments ?: "" } ?: ""
                when (val loopResult = checkLoopAndInterrupt(
                    iteration = iteration,
                    loopDetector = loopDetector,
                    turnFailureTracker = turnFailureTracker,
                    toolCallNames = choice.message.toolCalls?.map { it.function.name },
                    textContent = choice.message.content,
                    toolCallArgsJson = nonStreamToolArgs,
                    resultText = ""
                )) {
                    is LoopCheckResult.Warn -> {
                        if (loopResult.shouldEscalate) {
                            escalatedThisTurn = true
                            Timber.w("Loop → escalating to complex model: ${profile.complexModelName}")
                            return@repeat
                        }
                        messages.add(ModelMessage(role = "system", content = "你似乎进入了死循环，请回忆本次任务的目标并注意你的行为是否符合目标"))
                    }
                    is LoopCheckResult.Hard -> {
                        val loopNotice = "\n\n⚠️ 检测到模型进入死循环（连续重复相似操作），已自动停止任务。请检查任务描述或调整智能体配置。"
                        val fullReply = loopResult.partialReply + loopNotice
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        val replyMessage = ChannelMessage(content = fullReply, senderId = profile.agentId, sessionId = sessionId)
                        channelManager.broadcast(replyMessage, excludeChannel = channelId)
                        return@runCatching
                    }
                    LoopCheckResult.None -> {}
                }

                if (choice.finishReason == "stop" && choice.message.toolCalls.isNullOrEmpty()) {
                    if (shouldAutoContinue(choice.message.content, autoContinueExtraCount)) {
                        autoContinueExtraCount++
                        val hint = autoContinueSystemHint()
                        val tail = autoContinueTailContext(choice.message.content)
                        val hintMsg = buildString {
                            append(hint)
                            if (tail.isNotEmpty()) {
                                append("\n\n<previous-assistant-tail>\n")
                                append(tail)
                                append("\n</previous-assistant-tail>")
                            }
                        }
                        val fullReply = if (!choice.message.reasoningContent.isNullOrBlank() && choice.message.content.isNotEmpty()) {
                            "⋞${choice.message.reasoningContent}⋟\n${choice.message.content}"
                        } else {
                            choice.message.content
                        }
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        messages.add(ModelMessage(role = "assistant", content = choice.message.content))
                        messages.add(ModelMessage(role = "system", content = hintMsg))
                        Timber.d("Auto-continue: text-only (${autoContinueExtraCount}/${AUTO_CONTINUE_MAX_EXTRA}); session=$sessionId")
                        return@repeat
                    }
                    val needsProResult = com.lin.hippyagent.core.model.routing.NeedsProDetector.detect(
                        choice.message.content ?: ""
                    )
                    val xmlModelSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModelSwitch(
                        choice.message.content ?: ""
                    )
                    val xmlModeSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModeSwitch(
                        choice.message.content ?: ""
                    )
                    val wantsComplex = needsProResult.hasMarker || xmlModelSwitch == "complex"
                    if (wantsComplex && profile.complexModelName.isNotEmpty() && !escalatedThisTurn) {
                        escalatedThisTurn = true
                        Timber.w("Model escalation requested: $needsProResult / xmlModel=$xmlModelSwitch → complex ${profile.complexModelName}")
                        val cleanedContent = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.stripAll(
                            com.lin.hippyagent.core.model.routing.NeedsProDetector.stripMarker(choice.message.content ?: "")
                        )
                        if (cleanedContent.isNotBlank()) {
                            messages.add(ModelMessage(role = "assistant", content = cleanedContent))
                        }
                        return@repeat
                    }
                    if (xmlModeSwitch != null) {
                        val targetMode = xmlModeSwitch.uppercase()
                        Timber.w("Agent ${profile.agentId} declared mode switch → $targetMode (next turn takes effect)")
                        updateSessionState(sessionId) { it.copy(modeOverride = targetMode) }
                    }
                    handleTextReply(
                        com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.stripAll(choice.message.content ?: ""),
                        choice.message.reasoningContent,
                        sessionId,
                        channelId
                    )
                    return@runCatching
                }

                if (!choice.message.toolCalls.isNullOrEmpty()) {
                    val tcResult = handleToolCalls(
                        toolCalls = choice.message.toolCalls,
                        content = choice.message.content ?: "",
                        reasoningContent = choice.message.reasoningContent,
                        sessionId = sessionId,
                        channelId = channelId,
                        messages = messages,
                        isLastIteration = isLastIteration,
                        escalatedThisTurn = escalatedThisTurn,
                        turnFailureTracker = turnFailureTracker,
                        inputGuard = inputGuard,
                        traceId = traceId
                    )
                    escalatedThisTurn = tcResult.escalatedThisTurn
                    // 后台补判：tool 调用 > 1 次 → 自动切复杂任务模型 (本轮剩余使用)
                    val sessionToolCount = _state.value.getSessionState(sessionId).toolCallCount
                    if (!escalatedThisTurn && sessionToolCount > 1 && profile.complexModelName.isNotEmpty()) {
                        escalatedThisTurn = true
                        Timber.w("Backend escalation: toolCallCount=$sessionToolCount > 1, switching to complex model ${profile.complexModelName}")
                    }
                    if (tcResult.shouldReturn) return@runCatching
                } else {
                    val reply = choice.message.content
                    if (reply.isNotEmpty()) {
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, reply, senderId = profile.agentId)
                        val replyMessage = ChannelMessage(
                            content = reply,
                            senderId = profile.agentId,
                            sessionId = sessionId
                        )
                        channelManager.broadcast(replyMessage, excludeChannel = channelId)
                    }
                    return@runCatching
                }
            }
        }.also { result ->
            piiMasker.clear()
            sessionContexts[sessionId]?.job = null
            val currentState = _state.value.getSessionState(sessionId)
            if (currentState.status == AgentStatus.STOPPED) {
                updateSessionState(sessionId) { it.copy(isThinking = false) }
            } else {
                updateSessionState(sessionId) {
                    it.copy(
                        status = if (result.isSuccess) AgentStatus.IDLE else AgentStatus.ERROR,
                        isThinking = false,
                        lastError = if (result.isFailure) result.exceptionOrNull()?.message else it.lastError,
                        messageCount = it.messageCount + 1
                    )
                }
            }
            result.onFailure { e ->
                Timber.e(e, "Agent ${profile.agentId} failed to process message")
                val errorMsg = when {
                    e is NetworkUnavailableException -> "📡 网络不可用，消息已缓存"
                    e is kotlinx.coroutines.TimeoutCancellationException -> "⚠️ 请求超时，请稍后重试"
                    e.message?.contains("401") == true -> "⚠️ API 密钥无效，请检查模型提供商配置"
                    e.message?.contains("429") == true -> "⚠️ 请求过于频繁，请稍后重试"
                    else -> "⚠️ 网络错误：${e.message?.take(100) ?: "未知错误"}"
                }
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
                channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
            }
            if (result.isSuccess) {
                triggerMemoryExtraction(sessionId)
            }
            runAfterAgent(sessionId, capturedMessages ?: mutableListOf())
            sessionContexts.remove(sessionId)
        }
    }

/**
     * B3 隐私模式：所有 LLM 调用强制走端侧模型，数据不出设备。
     *
     * 与正常 [doProcessMessage] 的区别：
     * - 不走 ModelRouter 路由，直接用 profile.fallbackModelName（配置为端侧模型）
     * - 不调用云端 LLM，不进行模型升级（escalation）
     * - 失败不降级到云端（隐私优先）
     *
     * 状态清理（updateSessionState IDLE/ERROR、sessionContexts.remove 等）由
     * [doProcessMessage] 的 runCatching.also 块统一处理，本方法只负责循环逻辑。
     */
internal suspend fun Agent.runLoopOnDeviceOnly(
        sessionId: String,
        channelId: String,
        content: String,
        skipUserMessage: Boolean,
        systemPromptSuffix: String?
    ): Result<Unit> = runCatching {
        Timber.d("Agent ${profile.agentId} processing message in PRIVACY MODE: $content")

        val onDeviceModel = profile.fallbackModelName.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("隐私模式需要配置端侧 fallback 模型")
        val onDeviceProvider = profile.fallbackModelProvider
            .takeIf { it.startsWith("ondevice-") }
            ?: throw IllegalStateException("隐私模式需要配置端侧 fallback provider (ondevice-*)")

        onDeviceModelManager?.ensureEngineLoaded(onDeviceModel)

        val traceId = coroutineContext[TraceContextElement]?.traceId ?: UUID.randomUUID().toString()
        val piiMasker = PiiMasker()
        val inputGuard = InputGuard(piiMasker, traceId)
        val outputValidator = OutputValidator(piiMasker)
        var consecutiveValidationFailures = 0

        val guardedInput = inputGuard.guard(content, InjectionDetector.DetectionResult.Source.USER_INPUT)
        for (detection in guardedInput.detections) {
            SecuritySpanReporter.report(
                type = when (detection.type) {
                    InputGuard.Detection.DetectionType.PII_MASKED -> SecuritySpanReporter.SecurityEventType.PII_MASKED
                    InputGuard.Detection.DetectionType.INJECTION_DETECTED -> SecuritySpanReporter.SecurityEventType.INJECTION_DETECTED
                    InputGuard.Detection.DetectionType.JAILBREAK_DETECTED -> SecuritySpanReporter.SecurityEventType.JAILBREAK_DETECTED
                },
                severity = detection.severity,
                traceId = traceId,
                source = detection.source.name,
                ruleId = detection.ruleId,
                matchedSnippet = detection.matchedSnippet,
                blocked = detection.blocked
            )
        }
        val guardedContent = guardedInput.processedText

        val ctx = prepareMessageContext(sessionId, channelId, guardedContent, onDeviceProvider, skipUserMessage, systemPromptSuffix, onDeviceModel, false)
            ?: throw NetworkUnavailableException("网络连接不可用，消息已缓存")

        val effectiveClient = ctx.effectiveClient
        val messages = ctx.messages
        val toolDefinitions = ctx.toolDefinitions

        val loopDetector = com.lin.hippyagent.core.agent.loop.ToolLoopDetection().also { detector ->
            com.lin.hippyagent.core.agent.loop.ToolLoopDetection.DEFAULT_POLL_TOOLS.forEach {
                detector.registerPollTool(it)
            }
        }
        var autoContinueExtraCount = 0
        val turnFailureTracker = com.lin.hippyagent.core.model.routing.TurnFailureTracker()
        val routedModel = stripModelPrefix(onDeviceModel)

        repeat(profile.running.maxIters) { iteration ->
            if (isSessionInterrupted(sessionId)) {
                Timber.d("Agent ${profile.agentId} session $sessionId interrupted at iteration $iteration (privacy mode)")
                clearSessionInterrupt(sessionId)
                return@runCatching
            }

            val isLastIteration = iteration >= profile.running.maxIters - 1
            if (isLastIteration) {
                messages.add(ModelMessage(
                    role = "system",
                    content = "⚠️ 注意：这是你本次任务的最后一次迭代机会。请立即总结你目前的工作成果和进度，包括已完成的部分和尚未完成的部分。不要继续调用工具，直接给出总结。"
                ))
            }
            val effectiveMessages = runBeforeModel(sessionId, messages, iteration)

            val request = ModelCallRequest(
                model = routedModel,
                messages = effectiveMessages,
                temperature = 0.7f,
                maxTokens = resolveModelMaxTokens() ?: profile.running.maxOutputTokens,
                tools = toolDefinitions
            )

            val resp = callLlmWithRetryAndRateLimit(request, effectiveClient)

            resp.usage?.let { usage ->
                _tokenUsage.update { tu ->
                    tu.copy(
                        inputTokens = tu.inputTokens + usage.promptTokens.toLong(),
                        outputTokens = tu.outputTokens + usage.completionTokens.toLong(),
                        totalTokens = tu.totalTokens + usage.totalTokens.toLong(),
                        apiCalls = tu.apiCalls + 1,
                        cacheReadTokens = tu.cacheReadTokens + usage.cacheReadTokens.toLong(),
                        cacheWriteTokens = tu.cacheWriteTokens + usage.cacheWriteTokens.toLong()
                    )
                }
                tokenUsageManager?.recordUsage(
                    providerId = onDeviceProvider,
                    modelName = request.model,
                    inputTokens = usage.promptTokens,
                    outputTokens = usage.completionTokens,
                    agentId = profile.agentId,
                    cacheReadTokens = usage.cacheReadTokens,
                    cacheWriteTokens = usage.cacheWriteTokens
                )
            }

            val registeredToolNames = toolDefinitions.map { it.name }.toSet()
            val validationResult = outputValidator.validate(resp, registeredToolNames)
            if (!validationResult.valid) {
                consecutiveValidationFailures++
                SecuritySpanReporter.report(
                    type = SecuritySpanReporter.SecurityEventType.OUTPUT_VALIDATION_FAILED,
                    severity = RiskLevel.HIGH,
                    traceId = traceId,
                    source = "LLM_OUTPUT",
                    blocked = true,
                    ruleId = validationResult.errors.joinToString(";")
                )
                if (consecutiveValidationFailures >= 3) {
                    throw RuntimeException("连续 3 次 LLM 输出校验失败，终止")
                }
                return@repeat
            }
            consecutiveValidationFailures = 0

            val choice = resp.choices.firstOrNull()
                ?: throw IllegalStateException("No choices in response")

            val nonStreamToolArgs = choice.message.toolCalls?.joinToString("|") { it.function.arguments ?: "" } ?: ""
            when (val loopResult = checkLoopAndInterrupt(
                iteration = iteration,
                loopDetector = loopDetector,
                turnFailureTracker = turnFailureTracker,
                toolCallNames = choice.message.toolCalls?.map { it.function.name },
                textContent = choice.message.content,
                toolCallArgsJson = nonStreamToolArgs,
                resultText = ""
            )) {
                is LoopCheckResult.Warn -> {
                    // 隐私模式不升级到云端，仅注入提示
                    if (loopResult.shouldEscalate) {
                        Timber.w("Loop detected in privacy mode but escalation skipped (no cloud fallback)")
                    }
                    messages.add(ModelMessage(role = "system", content = "你似乎进入了死循环，请回忆本次任务的目标并注意你的行为是否符合目标"))
                }
                is LoopCheckResult.Hard -> {
                    val loopNotice = "\n\n⚠️ 检测到模型进入死循环（连续重复相似操作），已自动停止任务。请检查任务描述或调整智能体配置。"
                    val fullReply = loopResult.partialReply + loopNotice
                    sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                    val replyMessage = ChannelMessage(content = fullReply, senderId = profile.agentId, sessionId = sessionId)
                    channelManager.broadcast(replyMessage, excludeChannel = channelId)
                    return@runCatching
                }
                LoopCheckResult.None -> {}
            }

            if (choice.finishReason == "stop" && choice.message.toolCalls.isNullOrEmpty()) {
                if (shouldAutoContinue(choice.message.content, autoContinueExtraCount)) {
                    autoContinueExtraCount++
                    val hint = autoContinueSystemHint()
                    val tail = autoContinueTailContext(choice.message.content)
                    val hintMsg = buildString {
                        append(hint)
                        if (tail.isNotEmpty()) {
                            append("\n\n<previous-assistant-tail>\n")
                            append(tail)
                            append("\n</previous-assistant-tail>")
                        }
                    }
                    val fullReply = if (!choice.message.reasoningContent.isNullOrBlank() && choice.message.content.isNotEmpty()) {
                        "⋞${choice.message.reasoningContent}⋟\n${choice.message.content}"
                    } else {
                        choice.message.content
                    }
                    sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                    messages.add(ModelMessage(role = "assistant", content = choice.message.content))
                    messages.add(ModelMessage(role = "system", content = hintMsg))
                    Timber.d("Auto-continue(privacy): text-only (${autoContinueExtraCount}/${AUTO_CONTINUE_MAX_EXTRA}); session=$sessionId")
                    return@repeat
                }
                handleTextReply(
                    com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.stripAll(choice.message.content ?: ""),
                    choice.message.reasoningContent,
                    sessionId,
                    channelId
                )
                return@runCatching
            }

            if (!choice.message.toolCalls.isNullOrEmpty()) {
                val tcResult = handleToolCalls(
                    toolCalls = choice.message.toolCalls,
                    content = choice.message.content ?: "",
                    reasoningContent = choice.message.reasoningContent,
                    sessionId = sessionId,
                    channelId = channelId,
                    messages = messages,
                    isLastIteration = isLastIteration,
                    escalatedThisTurn = false,
                    turnFailureTracker = turnFailureTracker,
                    inputGuard = inputGuard,
                    traceId = traceId
                )
                if (tcResult.shouldReturn) return@runCatching
            } else {
                val reply = choice.message.content
                if (reply.isNotEmpty()) {
                    sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, reply, senderId = profile.agentId)
                    val replyMessage = ChannelMessage(
                        content = reply,
                        senderId = profile.agentId,
                        sessionId = sessionId
                    )
                    channelManager.broadcast(replyMessage, excludeChannel = channelId)
                }
                return@runCatching
            }
        }
    }

internal suspend fun Agent.handleTextReply(
        content: String?,
        reasoningContent: String?,
        sessionId: String,
        channelId: String,
        thinkingDurationMs: Long = 0L
    ) {
        val reply = content ?: ""
        val fullReply = if (!reasoningContent.isNullOrBlank() && reply.isNotEmpty()) {
            "⋞${reasoningContent}⋟\n$reply"
        } else if (!reasoningContent.isNullOrBlank()) {
            "⋞${reasoningContent}⋟"
        } else {
            reply
        }
        if (fullReply.isNotEmpty()) {
            val assistantMsg = sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId).getOrNull()
            if (assistantMsg != null && thinkingDurationMs > 0L) {
                val existingMeta = assistantMsg.metadataJson?.let {
                    try {
                        val obj = kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject
                        obj?.mapValues { (_, v) -> v.jsonPrimitive.content }
                    } catch (_: Exception) { null }
                }
                val metaJson = buildMetaJson(existingMeta, thinkingDurationMs)
                if (metaJson.isNotEmpty()) {
                    sessionStore.updateMessageMetadata(assistantMsg.id, metaJson)
                }
            }
            val replyMessage = ChannelMessage(
                content = reply,
                senderId = profile.agentId,
                sessionId = sessionId
            )
            channelManager.broadcast(replyMessage, excludeChannel = channelId)
        }
    }
