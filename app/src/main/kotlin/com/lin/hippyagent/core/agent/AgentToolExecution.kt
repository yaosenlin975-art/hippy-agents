package com.lin.hippyagent.core.agent

import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionToolCall
import com.lin.hippyagent.core.agent.session.ToolCallStatus
import com.lin.hippyagent.core.channel.ChannelMessage
import com.lin.hippyagent.core.model.FunctionInfo
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ToolCallInfo
import com.lin.hippyagent.core.security.InputGuard
import com.lin.hippyagent.core.security.RiskLevel
import com.lin.hippyagent.core.security.SecuritySpanReporter
import com.lin.hippyagent.core.security.injection.InjectionDetector
import com.lin.hippyagent.core.tools.ToolCall
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolResult
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanContext
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceContextElement
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber

internal class AccumulatedToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

internal class AccumulatedToolCallPool(private val maxSize: Int = 8) {
        private val pool = java.util.ArrayDeque<AccumulatedToolCall>()

        fun acquire(): AccumulatedToolCall {
            val obj = pool.pollFirst()
            return if (obj != null) {
                obj.id = ""
                obj.name = ""
                obj.arguments.clear()
                obj
            } else {
                AccumulatedToolCall()
            }
        }

        fun release(obj: AccumulatedToolCall) {
            if (pool.size < maxSize) {
                obj.id = ""
                obj.name = ""
                obj.arguments.clear()
                pool.addLast(obj)
            }
        }
    }

internal fun Agent.mergeToolCallDeltas(
        accumulated: android.util.SparseArray<AccumulatedToolCall>,
        deltas: List<ToolCallInfo>
    ) {
        for (delta in deltas) {
            val idx = if (delta.index >= 0) delta.index else accumulated.size()
            val acc = accumulated.get(idx) ?: run {
                val new = accumulatedToolCallPool.acquire()
                accumulated.put(idx, new)
                new
            }
            if (delta.id.isNotBlank() && delta.id != "unknown") acc.id = delta.id
            if (delta.function.name.isNotBlank()) acc.name = delta.function.name
            acc.arguments.append(delta.function.arguments)
        }
    }

internal fun Agent.buildFinalToolCalls(
        accumulated: android.util.SparseArray<AccumulatedToolCall>,
        out: MutableList<ToolCallInfo>
    ) {
        for (i in 0 until accumulated.size()) {
            val idx = accumulated.keyAt(i)
            val acc = accumulated.valueAt(i)
            out.add(ToolCallInfo(
                id = acc.id.ifBlank { "call_$idx" },
                function = FunctionInfo(
                    name = acc.name,
                    arguments = acc.arguments.toString()
                ),
                index = idx
            ))
        }
    }

internal suspend fun Agent.handleToolCalls(
        toolCalls: List<ToolCallInfo>,
        content: String,
        reasoningContent: String?,
        sessionId: String,
        channelId: String,
        messages: MutableList<ModelMessage>,
        isLastIteration: Boolean,
        escalatedThisTurn: Boolean,
        turnFailureTracker: com.lin.hippyagent.core.model.routing.TurnFailureTracker,
        thinkingDurationMs: Long = 0L,
        inputGuard: InputGuard? = null,
        traceId: String? = null
    ): ToolCallResult {
        val allowedToolNames = toolRegistry.getDefinitionsForAgent(
            agentId = profile.agentId
        ).map { it.name }.toSet()
        val repairResult = repairPipeline.repair(
            toolCalls = toolCalls,
            content = content,
            reasoningContent = reasoningContent,
            allowedToolNames = allowedToolNames
        )
        var currentEscalated = escalatedThisTurn
        if (repairResult.scavenged > 0 || repairResult.truncationsFixed > 0 || repairResult.stormsBroken > 0) {
            Timber.d("ToolCallRepair: scavenged=${repairResult.scavenged}, truncated=${repairResult.truncationsFixed}, storms=${repairResult.stormsBroken}")
            val escalated = turnFailureTracker.noteFailure(
                com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.SCAVENGED
            )
            if (escalated && profile.complexModelName.isNotEmpty() && !currentEscalated) {
                currentEscalated = true
                Timber.w("SCAVENGED → escalating to complex model")
            }
        }
        val effectiveToolCalls = repairResult.repairedToolCalls.ifEmpty { toolCalls }

        if (isLastIteration) {
            val partialReply = content.ifEmpty { "" }
            val exhaustionNotice = "\n\n⚠️ 迭代轮次已耗尽（${profile.running.maxIters}轮），任务未能完全完成。以下是当前进度：\n${partialReply.ifBlank { "（智能体在最后一轮仍在调用工具，未能生成文字总结）" }}"
            val fullReply = partialReply + exhaustionNotice
            if (fullReply.isNotEmpty()) {
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                val replyMessage = ChannelMessage(
                    content = fullReply,
                    senderId = profile.agentId,
                    sessionId = sessionId
                )
                channelManager.broadcast(replyMessage, excludeChannel = channelId)
            }
            return ToolCallResult(currentEscalated, shouldReturn = true)
        }

        val rawContent = content.ifEmpty { "" }
        val assistantContent = if (!reasoningContent.isNullOrBlank()) {
            "⋞${reasoningContent}⋟\n$rawContent"
        } else {
            rawContent
        }
        val assistantMsg = sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, assistantContent, senderId = profile.agentId).getOrNull()
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
        if (assistantMsg != null) {
            for (toolCall in effectiveToolCalls) {
                sessionStore.addToolCall(sessionId, assistantMsg, SessionToolCall(
                    id = toolCall.id,
                    name = toolCall.function.name,
                    arguments = toolCall.function.arguments,
                    status = ToolCallStatus.RUNNING
                ))
            }
        }

        messages.add(ModelMessage(
            role = "assistant",
            content = assistantContent,
            toolCalls = effectiveToolCalls
        ))

        val toolResults = supervisorScope {
            effectiveToolCalls.map { toolCall ->
                async(Dispatchers.IO) {
                    toolCall to executeToolCall(toolCall, sessionId, channelId)
                }
            }.awaitAll()
        }

        for ((toolCall, toolResult) in toolResults) {
            val resultContent = toolResult?.output ?: toolResult?.error ?: ""
            val resultStatus = if (toolResult?.success == true) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
            if (toolResult?.success == false && resultContent.contains("search text not found", ignoreCase = true)) {
                val escalated = turnFailureTracker.noteFailure(
                    com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.SEARCH_MISMATCH
                )
                if (escalated && profile.complexModelName.isNotEmpty() && !currentEscalated) {
                    currentEscalated = true
                    Timber.w("SEARCH_MISMATCH → escalating to complex model")
                }
            }
            if (toolResult?.success == false && (resultContent.contains("truncated", ignoreCase = true) || resultContent.contains("JSON", ignoreCase = true))) {
                val escalated = turnFailureTracker.noteFailure(
                    com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.TRUNCATED
                )
                if (escalated && profile.complexModelName.isNotEmpty() && !currentEscalated) {
                    currentEscalated = true
                    Timber.w("TRUNCATED → escalating to complex model")
                }
            }
            if (assistantMsg != null) {
                sessionStore.updateToolCall(sessionId, assistantMsg.id, toolCall.id, resultStatus, resultContent)
            }
            sessionStore.addMessage(sessionId, MessageRole.TOOL, resultContent, toolName = toolCall.function.name, senderId = profile.agentId)
            Timber.d("Tool result: toolCallId=${toolCall.id}, name=${toolCall.function.name}, content=${resultContent.take(50)}")
            val llmToolContent = if (inputGuard != null && traceId != null) {
                val guardedToolOutput = inputGuard.guard(resultContent, InjectionDetector.DetectionResult.Source.TOOL_OUTPUT)
                for (detection in guardedToolOutput.detections) {
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
                guardedToolOutput.processedText
            } else {
                resultContent
            }
            messages.add(ModelMessage(
                role = "tool",
                content = llmToolContent,
                toolCallId = toolCall.id
            ))
            if (toolResult?.needsPermissionApproval == true) {
                updateSessionState(sessionId) { it.copy(pendingPermissionCommand = toolResult.permissionCommand) }
            }
            if (toolResult?.missingAndroidPermissions?.isNotEmpty() == true) {
                updateSessionState(sessionId) { it.copy(missingAndroidPermissions = toolResult.missingAndroidPermissions) }
            }
        }

        return ToolCallResult(currentEscalated, shouldReturn = false)
    }

internal suspend fun Agent.executeToolCall(
        toolCall: ToolCallInfo,
        sessionId: String,
        channelId: String
    ): ToolResult? {
        updateSessionState(sessionId) { it.copy(status = AgentStatus.EXECUTING_TOOL) }

        return try {
            val arguments = toolCall.function.arguments.let { argsJson ->
                try {
                    val jsonElement = Json.parseToJsonElement(argsJson)
                    if (jsonElement is JsonObject) {
                        jsonElement.mapValues { (_, value) ->
                            when (value) {
                                is JsonPrimitive -> when {
                                    value.isString -> value.content
                                    value.content == "true" -> true
                                    value.content == "false" -> false
                                    else -> value.content.toLongOrNull() ?: value.content.toDoubleOrNull() ?: value.content
                                }
                                else -> value.toString()
                            }
                        }
                    } else {
                        emptyMap()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse tool arguments JSON: $argsJson")
                    emptyMap()
                }
            }

            val pathKeys = setOf("path", "file_path", "filePath", "directory", "dir",
                "source", "destination", "dest", "output", "input", "filename", "uri")
            val resolvedArguments = arguments.mapValues { (key, value) ->
                if (key in pathKeys && value is String) {
                    resolveToolPath(value)
                } else {
                    value
                }
            }

            val toolCallObj = ToolCall(
                toolName = toolCall.function.name,
                arguments = resolvedArguments
            )

            toolGuardian?.let { guardian ->
                val workspaceDir = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
                val canonicalWorkspace = workspaceDir.canonicalPath
                val canonicalAppData = java.io.File("/data/user/0/${context?.packageName}").canonicalPath
                val workspacePaths = listOf(canonicalWorkspace + "/", canonicalAppData + "/")
                val securityCheck = guardian.checkToolCall(
                    toolCall.function.name, resolvedArguments,
                    workspacePaths = workspacePaths
                )
                guardian.logAudit(toolCall.function.name, resolvedArguments, securityCheck)

                if (!securityCheck.passed) {
                    Timber.w("ToolGuardian blocked tool call: ${toolCall.function.name}, reason: ${securityCheck.reason}")
                    approvalManager?.recordBlockedCall(
                        toolName = toolCall.function.name,
                        arguments = resolvedArguments,
                        reason = securityCheck.reason ?: "Security check failed",
                        agentId = profile.agentId,
                        sessionId = sessionId
                    )
                    return ToolResult(
                        callId = toolCall.id,
                        success = false,
                        output = "安全检查未通过: ${securityCheck.reason}",
                        error = "BLOCKED_BY_GUARDIAN: ${securityCheck.reason}"
                    )
                }

                if (securityCheck.riskLevel >= RiskLevel.HIGH) {
                    if (approvalManager != null) {
                        val existingRule = approvalManager.checkRule(toolCall.function.name, resolvedArguments)
                        val action = when {
                            existingRule == com.lin.hippyagent.core.security.ApprovalAction.ALLOW_ALWAYS -> com.lin.hippyagent.core.security.ApprovalAction.ALLOW_ALWAYS
                            existingRule == com.lin.hippyagent.core.security.ApprovalAction.DENY_ALWAYS -> com.lin.hippyagent.core.security.ApprovalAction.DENY_ALWAYS
                            else -> approvalManager.requestApproval(
                                toolName = toolCall.function.name,
                                arguments = resolvedArguments,
                                riskLevel = securityCheck.riskLevel,
                                findings = securityCheck.findings,
                                sessionId = sessionId,
                                agentId = profile.agentId
                            )
                        }

                        when (action) {
                            com.lin.hippyagent.core.security.ApprovalAction.DENY_ONCE,
                            com.lin.hippyagent.core.security.ApprovalAction.DENY_ALWAYS -> {
                                Timber.w("ToolGuardian approval denied: ${toolCall.function.name}, action: $action")
                                return ToolResult(
                                    callId = toolCall.id,
                                    success = false,
                                    output = "用户拒绝了此操作: ${securityCheck.reason}",
                                    error = "DENIED_BY_USER: ${securityCheck.reason}"
                                )
                            }
                            else -> {
                                Timber.i("ToolGuardian approval granted: ${toolCall.function.name}, action: $action")
                            }
                        }
                    } else {
                        Timber.e("ToolGuardian HIGH+ risk BLOCKED (no approval manager): ${toolCall.function.name}, reason: ${securityCheck.reason}")
                        return ToolResult(
                            callId = toolCall.id,
                            success = false,
                            output = "安全检查未通过（无审批管理器）: ${securityCheck.reason}",
                            error = "BLOCKED_NO_APPROVAL_MANAGER: ${securityCheck.reason}"
                        )
                    }
                }
            }

            val toolCtx = ToolContext(
                channel = channelId,
                sessionId = sessionId,
                agentId = profile.agentId,
                workspace = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
            )

            val traceCtx = coroutineContext[TraceContextElement]
            val toolSpan = if (traceCtx != null) {
                SpanCollector.startSpan(
                    type = SpanType.TOOL_CALL,
                    traceId = traceCtx.traceId,
                    parentSpanId = traceCtx.parentSpanId,
                    props = mapOf(
                        "toolName" to toolCall.function.name,
                        "args" to toolCall.function.arguments
                    )
                )
            } else {
                SpanContext.NoOp
            }
            val result = try {
                val r = toolRegistry.executeTool(toolCallObj, toolCtx)
                SpanCollector.end(toolSpan, extraProps = mapOf(
                    "result" to (r.output ?: r.error ?: ""),
                    "retryCount" to 0
                ))
                r
            } catch (e: Exception) {
                SpanCollector.end(toolSpan, error = e.message)
                throw e
            }

            updateSessionState(sessionId) {
                it.copy(toolCallCount = it.toolCallCount + 1)
            }

            if (result.success) {
                Timber.d("Tool ${toolCall.function.name} executed successfully")
            } else {
                Timber.w("Tool ${toolCall.function.name} failed: ${result.error}")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute tool call: ${toolCall.function.name}")
            ToolResult(callId = toolCall.id, success = false, error = "工具执行异常: ${e.message}")
        } finally {
            updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
        }
    }

internal fun Agent.resolveToolPath(path: String): String {
        val file = java.io.File(path)
        val workspaceDir = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
        val resolved = if (file.isAbsolute) file else java.io.File(workspaceDir, path)
        val canonicalWorkspace = workspaceDir.canonicalPath
        val canonicalPath = resolved.canonicalPath
        if (!canonicalPath.startsWith(canonicalWorkspace) && !canonicalPath.startsWith(java.io.File(storageManager.getWorkingDir(), "skills").canonicalPath)) {
            throw SecurityException("Access denied: path '$path' is outside agent workspace")
        }
        return canonicalPath
    }

internal fun Agent.checkLoopAndInterrupt(
        iteration: Int,
        loopDetector: com.lin.hippyagent.core.agent.loop.ToolLoopDetection,
        turnFailureTracker: com.lin.hippyagent.core.model.routing.TurnFailureTracker,
        toolCallNames: List<String>?,
        textContent: String,
        toolCallArgsJson: String = "",
        resultText: String = "",
        isStream: Boolean = false
    ): LoopCheckResult {
        val tag = if (isStream) "(stream)" else ""
        val detection = loopDetector.checkAndRecord(
            toolName = toolCallNames?.firstOrNull() ?: "unknown",
            paramsJson = toolCallArgsJson,
            resultText = resultText
        )
        when (detection.level) {
            com.lin.hippyagent.core.agent.loop.ToolLoopDetection.LoopLevel.WARN -> {
                Timber.w("Loop warning${tag} for agent ${profile.agentId} at iteration $iteration: ${detection.message}")
                val escalated = turnFailureTracker.noteFailure(
                    com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.REPEAT_LOOP
                )
                return LoopCheckResult.Warn(shouldEscalate = escalated && profile.complexModelName.isNotEmpty())
            }
            com.lin.hippyagent.core.agent.loop.ToolLoopDetection.LoopLevel.CRITICAL -> {
                Timber.w("Loop hard limit${tag} for agent ${profile.agentId} at iteration $iteration: ${detection.message}")
                return LoopCheckResult.Hard(
                    partialReply = textContent.ifEmpty { "" },
                    hasToolCalls = !toolCallNames.isNullOrEmpty()
                )
            }
            com.lin.hippyagent.core.agent.loop.ToolLoopDetection.LoopLevel.NONE -> {}
        }
        return LoopCheckResult.None
    }

internal data class ToolCallResult(
        val escalatedThisTurn: Boolean,
        val shouldReturn: Boolean
    )

internal sealed class LoopCheckResult {
        data object None : LoopCheckResult()
        data class Warn(val shouldEscalate: Boolean) : LoopCheckResult()
        data class Hard(val partialReply: String, val hasToolCalls: Boolean) : LoopCheckResult()
    }
