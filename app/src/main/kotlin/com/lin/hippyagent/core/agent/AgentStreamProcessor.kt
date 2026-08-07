package com.lin.hippyagent.core.agent

import com.lin.hippyagent.core.agent.middleware.MiddlewareResult
import com.lin.hippyagent.core.agent.middleware.ModelResponse
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.channel.ChannelMessage
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceContextElement
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
     * 流式处理消息 - 返回 AI 回复的 Flow
     * 正常文本流式输出；遇到 tool call 时执行工具后继续下一轮
     *
     * 使用 per-session 互斥锁，允许不同会话并行处理。
     * 同一会话的消息排队等待（withLock），不会丢失。
     */
internal fun Agent.processMessageStream(
        sessionId: String,
        channelId: String,
        content: String,
        overrideModel: String? = null,
        overrideProviderId: String? = null,
        planContext: String? = null,
        skipUserMessage: Boolean = false,
        systemPromptSuffix: String? = null,
        forceEscalate: Boolean = false,
    ): Flow<StreamChunk> = flow {
        val sessionMutex = getOrCreateSessionMutex(sessionId)

        // 同一会话的消息排队等待，而非直接拒绝（WS-20 修复）：
        // 忙时挂起排队等待锁释放（flow builder 为 suspend 上下文，可直接挂起），
        // 不再 tryLock 失败抛 IllegalStateException；同时记录是否持锁，
        // finally 仅在实际持锁时 unlock()，避免对未持有的 mutex 调用 unlock() 崩溃
        var mutexHeld = false
        if (sessionMutex.tryLock()) {
            mutexHeld = true
        } else {
            // 会话内已有请求在处理：挂起等待锁释放（排队），而非抛异常
            sessionMutex.lock()
            mutexHeld = true
        }

        var streamFailed = false
        var capturedMessages: MutableList<ModelMessage>? = null
        var afterAgentCalled = false
        try {
            getOrCreateSessionContext(sessionId).job = requireNotNull(coroutineContext[Job]) { "Agent stream requires a coroutine Job in context" }
            val ctx = prepareMessageContext(sessionId, channelId, content, overrideProviderId, skipUserMessage, systemPromptSuffix, overrideModel, forceEscalate)
            if (ctx == null) {
                emit(StreamChunk.Content("📡 网络不可用，消息已缓存，网络恢复后自动发送"))
                return@flow
            }

            val effectiveClient = ctx.effectiveClient
            val messages = ctx.messages
            capturedMessages = messages
            val toolDefinitions = ctx.toolDefinitions
            var escalatedThisTurn = ctx.escalatedThisTurn

            ctx.compactionStartedInfo?.let { emit(it) }
            ctx.compactionInfo?.let { emit(it) }
            ctx.compactionCompletedInfo?.let { emit(it) }

            updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING, isThinking = true) }
            sessionManager?.createSession(sessionId, profile.agentId, channelId)
            sessionManager?.updateActivity(sessionId)

            synchronized(toolRegistry.deferredToolRegistry) {
                // NOTE: This clears the shared singleton. Consider per-agent deferred set to avoid cross-agent interference.
                toolRegistry.deferredToolRegistry.clear()
                for (def in toolRegistry.getDeferredToolNames()) {
                    val toolDef = toolRegistry.getToolDefinition(def) ?: continue
                    toolRegistry.deferredToolRegistry.register(
                        com.lin.hippyagent.core.model.ModelToolDefinition(
                            name = toolDef.name,
                            description = toolDef.description,
                            parameters = Agent.buildToolParameterSchema(toolDef.parameters)
                        )
                    )
                }
            }
            var apiCallCount = 0
            var estimatedInputTokens = 0L
            var estimatedOutputTokens = 0L
            var iterationExhausted = false
            val loopDetector = com.lin.hippyagent.core.agent.loop.ToolLoopDetection().also { detector ->
                // 注册默认轮询类工具
                com.lin.hippyagent.core.agent.loop.ToolLoopDetection.DEFAULT_POLL_TOOLS.forEach {
                    detector.registerPollTool(it)
                }
            }
            var autoContinueExtraCount = 0
            val turnFailureTracker = com.lin.hippyagent.core.model.routing.TurnFailureTracker()
            repeat(profile.running.maxIters) { iteration ->
                if (iteration > 0) {
                    emit(StreamChunk.NewIteration)
                }
                if (isSessionInterrupted(sessionId)) {
                    clearSessionInterrupt(sessionId)
                    return@flow
                }

                val isLastIteration = iteration >= profile.running.maxIters - 1
                if (isLastIteration) {
                    messages.add(ModelMessage(
                        role = "system",
                        content = "⚠️ 注意：这是你本次任务的最后一次迭代机会。请立即总结你目前的工作成果和进度，包括已完成的部分和尚未完成的部分。不要继续调用工具，直接给出总结。"
                    ))
                }
                var effectiveMessages = runBeforeModel(sessionId, messages, iteration)

                val routed = resolveRoutedModel(sessionId, content, overrideModel, escalatedThisTurn, effectiveClient, isStream = true, hasTools = toolDefinitions.isNotEmpty())
                val routedModel = routed.modelName
                val routingClient = routed.client

                val request = ModelCallRequest(
                    model = routedModel,
                    messages = effectiveMessages,
                    temperature = 0.7f,
                    maxTokens = resolveModelMaxTokens() ?: profile.running.maxOutputTokens,
                    tools = toolDefinitions,
                    stream = true
                )

                val fullContent = sbPool.acquire()
                val reasoningContent = sbPool.acquire()
                val toolCallAccumulator = android.util.SparseArray<AccumulatedToolCall>()
                reusableToolCallList.clear()
                var lastEmitTime = 0L
                val emitIntervalMs = 150L
                val contentBatch = sbPool.acquire()
                val thinkingBatch = sbPool.acquire()
                var thinkingStartTime = 0L
                try {
                kotlinx.coroutines.withTimeout(300_000L) {
                routingClient.chatCompletionStream(request).collect { chunk ->
                    val delta = chunk.choices.firstOrNull()?.delta ?: return@collect
                    val now = System.currentTimeMillis()
                    delta.content?.let {
                        if (it.isNotEmpty()) {
                            fullContent.append(it)
                            contentBatch.append(it)
                        }
                    }
                    delta.reasoningContent?.let {
                        if (it.isNotEmpty()) {
                            if (thinkingStartTime == 0L) thinkingStartTime = System.currentTimeMillis()
                            reasoningContent.append(it)
                            thinkingBatch.append(it)
                        }
                    }
                    delta.toolCalls?.let { tc -> mergeToolCallDeltas(toolCallAccumulator, tc) }
                    if (now - lastEmitTime >= emitIntervalMs) {
                        if (contentBatch.isNotEmpty()) {
                            emit(StreamChunk.Content(contentBatch.toString()))
                            contentBatch.clear()
                        }
                        if (thinkingBatch.isNotEmpty()) {
                            emit(StreamChunk.Thinking(thinkingBatch.toString()))
                            thinkingBatch.clear()
                        }
                        lastEmitTime = now
                    }
                }
                }
                apiCallCount++
                if (fullContent.isEmpty() && reasoningContent.isEmpty() && toolCallAccumulator.size() == 0) {
                    Timber.w("Agent ${profile.agentId} iteration $iteration: empty response from model, skipping...")
                }
                val iterInputTokens = effectiveMessages.sumOf { (it.content.length / 3.5).toLong().coerceAtLeast(1) }
                val iterOutputTokens = (fullContent.length / 3.5).toLong().coerceAtLeast(1) + (reasoningContent.length / 3.5).toLong().coerceAtLeast(1)
                estimatedInputTokens += iterInputTokens
                estimatedOutputTokens += iterOutputTokens
                _tokenUsage.update { tu ->
                    tu.copy(
                        inputTokens = tu.inputTokens + iterInputTokens,
                        outputTokens = tu.outputTokens + iterOutputTokens,
                        totalTokens = tu.totalTokens + iterInputTokens + iterOutputTokens,
                        apiCalls = tu.apiCalls + 1
                    )
                }
                tokenUsageManager?.recordUsage(
                    providerId = profile.modelProvider,
                    modelName = request.model,
                    inputTokens = iterInputTokens.toInt(),
                    outputTokens = iterOutputTokens.toInt(),
                    agentId = profile.agentId
                )
                if (contentBatch.isNotEmpty()) {
                    emit(StreamChunk.Content(contentBatch.toString()))
                }
                if (thinkingBatch.isNotEmpty()) {
                    emit(StreamChunk.Thinking(thinkingBatch.toString()))
                }

                val thinkingDurationMs = if (thinkingStartTime > 0L && reasoningContent.isNotEmpty()) {
                    System.currentTimeMillis() - thinkingStartTime
                } else 0L

                reusableToolCallList.clear()
                buildFinalToolCalls(toolCallAccumulator, reusableToolCallList)

                // 释放 AccumulatedToolCall 对象回池，避免 GC 压力
                for (i in 0 until toolCallAccumulator.size()) {
                    accumulatedToolCallPool.release(toolCallAccumulator.valueAt(i))
                }
                toolCallAccumulator.clear()

                val reasoningPrefix = reasoningContent.toString().ifBlank { null }?.let { "⋞$it⋟\n" } ?: ""

                val modelResponse = ModelResponse(
                    content = fullContent.toString(),
                    toolCalls = reusableToolCallList.toList().ifEmpty { null },
                    finishReason = if (reusableToolCallList.isEmpty()) "stop" else "tool_calls"
                )
                when (val afterResult = runAfterModel(sessionId, messages, iteration, modelResponse)) {
                    is MiddlewareResult.Respond -> {
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, afterResult.message, senderId = profile.agentId)
                        emit(StreamChunk.Content(afterResult.message))
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    is MiddlewareResult.AbortTurn -> {
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    is MiddlewareResult.HardAbort -> {
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    else -> {}
                }

                val streamToolArgs = reusableToolCallList.joinToString("|") { it.function.arguments ?: "" }
                when (val loopResult = checkLoopAndInterrupt(
                    iteration = iteration,
                    loopDetector = loopDetector,
                    turnFailureTracker = turnFailureTracker,
                    toolCallNames = reusableToolCallList.map { it.function.name },
                    textContent = fullContent.toString(),
                    toolCallArgsJson = streamToolArgs,
                    resultText = "",
                    isStream = true
                )) {
                    is LoopCheckResult.Warn -> {
                        if (loopResult.shouldEscalate) {
                            escalatedThisTurn = true
                            Timber.w("Loop(stream) → escalating to complex model: ${profile.complexModelName}")
                            return@repeat
                        }
                        messages.add(ModelMessage(role = "system", content = "你似乎进入了死循环，请回忆本次任务的目标并注意你的行为是否符合目标"))
                    }
                    is LoopCheckResult.Hard -> {
                        if (loopResult.hasToolCalls) {
                            reusableToolCallList.clear()
                            messages.add(ModelMessage(role = "system", content = "⚠️ 你已连续重复相似操作超过硬限制。工具调用已被强制取消。请立即用文字总结当前进度和结果，不要再调用任何工具。"))
                            updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
                            return@repeat
                        }
                        val loopNotice = "\n\n⚠️ 检测到模型进入死循环（连续重复相似操作），已自动停止任务。请检查任务描述或调整智能体配置。"
                        val fullReply = reasoningPrefix + loopResult.partialReply + loopNotice
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        emit(StreamChunk.Content(loopNotice))
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    LoopCheckResult.None -> {}
                }

                if (reusableToolCallList.isEmpty()) {
                    // Auto-continue: nudge model when text-only mid-task
                    if (shouldAutoContinue(fullContent.toString(), autoContinueExtraCount)) {
                        autoContinueExtraCount++
                        val hint = autoContinueSystemHint()
                        val tail = autoContinueTailContext(fullContent.toString())
                        val hintMsg = buildString {
                            append(hint)
                            if (tail.isNotEmpty()) {
                                append("\n\n<previous-assistant-tail>\n")
                                append(tail)
                                append("\n</previous-assistant-tail>")
                            }
                        }
                        val reply = fullContent.toString()
                        val fullReply = reasoningPrefix + reply
                        if (fullReply.isNotEmpty()) {
                            sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        }
                        messages.add(ModelMessage(role = "assistant", content = reply))
                        messages.add(ModelMessage(role = "system", content = hintMsg))
                        updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
                        Timber.d("Auto-continue(stream): text-only (${autoContinueExtraCount}/${AUTO_CONTINUE_MAX_EXTRA}); session=$sessionId")
                        return@repeat
                    }
                    val rawReply = fullContent.toString()
                    val xmlModelSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModelSwitch(rawReply)
                    val xmlModeSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModeSwitch(rawReply)
                    val needsProResult = com.lin.hippyagent.core.model.routing.NeedsProDetector.detect(rawReply)
                    val wantsComplex = needsProResult.hasMarker || xmlModelSwitch == "complex"
                    if (wantsComplex && profile.complexModelName.isNotEmpty() && !escalatedThisTurn) {
                        escalatedThisTurn = true
                        Timber.w("Model escalation(stream): $needsProResult / xmlModel=$xmlModelSwitch → complex ${profile.complexModelName}")
                    }
                    if (xmlModeSwitch != null) {
                        val targetMode = xmlModeSwitch.uppercase()
                        Timber.w("Agent ${profile.agentId} declared mode switch (stream) → $targetMode (next turn takes effect)")
                        updateSessionState(sessionId) { it.copy(modeOverride = targetMode) }
                    }
                    val cleanedReply = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.stripAll(
                        com.lin.hippyagent.core.model.routing.NeedsProDetector.stripMarker(rawReply)
                    )
                    val fullReply = reasoningPrefix + cleanedReply
                    if (fullReply.isNotEmpty()) {
                        val assistantMsg = sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId).getOrNull()
                        if (assistantMsg != null && thinkingDurationMs > 0L) {
                            val metaJson = buildMetaJson(null, thinkingDurationMs)
                            if (metaJson.isNotEmpty()) {
                                sessionStore.updateMessageMetadata(assistantMsg.id, metaJson)
                            }
                        }
                        // 注意：前面的 Content chunks 已流式 emit 给 UI；此处不再重复 emit 以免用户看到重复。
                        // 通道广播使用清理后的内容, 避免将 XML 标签传播给其他频道接收者。
                        val replyMessage = ChannelMessage(
                            content = cleanedReply,
                            senderId = profile.agentId,
                            sessionId = sessionId
                        )
                        channelManager.broadcast(replyMessage, excludeChannel = channelId)
                    }
                    emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                    return@flow
                }

                if (isLastIteration && reusableToolCallList.isNotEmpty()) {
                    iterationExhausted = true
                    val partialReply = fullContent.toString()
                    val exhaustionNotice = "\n\n⚠️ 迭代轮次已耗尽（${profile.running.maxIters}轮），任务未能完全完成。以下是当前进度：\n${partialReply.ifBlank { "（智能体在最后一轮仍在调用工具，未能生成文字总结）" }}"
                    val fullReply = reasoningPrefix + partialReply + exhaustionNotice
                    sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                    emit(StreamChunk.Content(exhaustionNotice))
                    emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                    return@flow
                }

                updateSessionState(sessionId) { it.copy(status = AgentStatus.EXECUTING_TOOL) }

                val tcResult = handleToolCalls(
                    toolCalls = reusableToolCallList.toList(),
                    content = fullContent.toString(),
                    reasoningContent = reasoningContent.toString().ifBlank { null },
                    sessionId = sessionId,
                    channelId = channelId,
                    messages = messages,
                    isLastIteration = false,
                    escalatedThisTurn = escalatedThisTurn,
                    turnFailureTracker = turnFailureTracker,
                    thinkingDurationMs = thinkingDurationMs
                )
                escalatedThisTurn = tcResult.escalatedThisTurn
                // 后台补判：tool 调用 > 1 次 → 自动切复杂任务模型 (本轮剩余使用)
                val sessionToolCount = _state.value.getSessionState(sessionId).toolCallCount
                if (!escalatedThisTurn && sessionToolCount > 1 && profile.complexModelName.isNotEmpty()) {
                    escalatedThisTurn = true
                    Timber.w("Backend escalation(stream): toolCallCount=$sessionToolCount > 1, switching to complex model ${profile.complexModelName}")
                }
                } finally {
                    fullContent.clear()
                    sbPool.release(fullContent)
                    reasoningContent.clear()
                    sbPool.release(reasoningContent)
                    contentBatch.clear()
                    sbPool.release(contentBatch)
                    thinkingBatch.clear()
                    sbPool.release(thinkingBatch)
                    reusableToolCallList.clear()
                }

                emit(StreamChunk.Content("\n"))
                updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
            }
            emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
            runAfterAgent(sessionId, messages)
            afterAgentCalled = true
        } catch (e: OutOfMemoryError) {
            streamFailed = true
            Timber.e(e, "Agent ${profile.agentId} OOM — 建议缩短上下文或重启应用")
            val errorMsg = "⚠️ 内存不足 (OOM)：上下文过长，请缩短对话或重启应用"
            updateSessionState(sessionId) { it.copy(status = AgentStatus.ERROR, lastError = errorMsg) }
            sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
            channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
            runCatching { emit(StreamChunk.Content(errorMsg)) }
            return@flow
        } catch (e: Exception) {
            streamFailed = true
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                Timber.w(e, "Agent ${profile.agentId} stream timeout")
                val errorMsg = "⚠️ 请求超时，请稍后重试"
                updateSessionState(sessionId) { it.copy(status = AgentStatus.ERROR, lastError = errorMsg) }
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
                channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
                runCatching { emit(StreamChunk.Content(errorMsg)) }
                return@flow
            }
            if (e !is kotlinx.coroutines.CancellationException) {
                Timber.e(e, "Agent ${profile.agentId} failed to process stream message")
                val errorMsg = "⚠️ 网络错误：${e.message?.take(100) ?: "未知错误"}"
                updateSessionState(sessionId) { it.copy(status = AgentStatus.ERROR, lastError = errorMsg) }
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
                channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
                runCatching { emit(StreamChunk.Content(errorMsg)) }
                return@flow
            }
        } finally {
            // 先释放 mutex，再更新状态为 IDLE，防止 sendMessage 读到 IDLE 但 mutex 仍被锁住的竞态
            if (mutexHeld) {
                sessionMutex.unlock()
            }
            sessionContexts[sessionId]?.job = null
            _currentProcessingSessionId = null
            val currentSessionState = _state.value.getSessionState(sessionId)
            when (currentSessionState.status) {
                AgentStatus.STOPPED -> {
                    updateSessionState(sessionId) { it.copy(isThinking = false) }
                }
                AgentStatus.ERROR -> {
                    updateSessionState(sessionId) {
                        it.copy(isThinking = false, messageCount = it.messageCount + 1)
                    }
                }
                else -> {
                    updateSessionState(sessionId) {
                        it.copy(
                            status = AgentStatus.IDLE,
                            isThinking = false,
                            messageCount = it.messageCount + 1
                        )
                    }
                }
            }
            sessionContexts.remove(sessionId)
            if (!afterAgentCalled) {
                runAfterAgent(sessionId, capturedMessages ?: mutableListOf())
            }
            triggerMemoryExtraction(sessionId)
            val tu = _tokenUsage.value
            val effectiveModel = overrideModel ?: profile.modelName
            if (tu.apiCalls > 0) {
                val closeModel = stripModelPrefix(effectiveModel)
                val inputTok = tu.inputTokens.toInt()
                val outputTok = tu.outputTokens.toInt()
                val cacheReadTok = tu.cacheReadTokens.toInt()
                val cacheWriteTok = tu.cacheWriteTokens.toInt()
                if (streamFailed) {
                    sessionStore.failSession(sessionId, closeModel, inputTok, outputTok, cacheReadTok, cacheWriteTok, null, currentSessionState.lastError)
                } else {
                    sessionStore.closeSession(sessionId, closeModel, inputTok, outputTok, cacheReadTok, cacheWriteTok, null)
                }
            }
            if (tu.apiCalls > 0 && tokenUsageManager != null) {
                val providerId = overrideProviderId ?: profile.modelProvider
                tokenUsageManager.recordUsage(
                    providerId = providerId,
                    modelName = stripModelPrefix(effectiveModel),
                    inputTokens = tu.inputTokens.toInt(),
                    outputTokens = tu.outputTokens.toInt(),
                    agentId = profile.agentId
                )
            }
        }
    }.let { original ->
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
        flow {
            withContext(TraceContextElement(traceId, null)) {
                original.collect { emit(it) }
            }
        }.onCompletion { e ->
            SpanCollector.end(traceSpan, error = e?.message)
        }
    }
