package com.lin.hippyagent.core.agent

import android.content.Context
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.tools.LlmToolRouter
import com.lin.hippyagent.core.bootstrap.BootstrapHook
import com.lin.hippyagent.core.memory.QueryIntentClassifier
import com.lin.hippyagent.core.memory.commonmemory.toSearchIntent
import com.lin.hippyagent.core.model.ContextWindowGuard
import com.lin.hippyagent.core.model.FunctionInfo
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.ToolCallInfo
import com.lin.hippyagent.core.prompt.PromptContext
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanContext
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceContextElement
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

internal data class PreparedContext(
        val effectiveClient: ModelClient,
        val messages: MutableList<ModelMessage>,
        val toolDefinitions: List<com.lin.hippyagent.core.model.ModelToolDefinition>,
        val escalatedThisTurn: Boolean,
        val compactionStartedInfo: StreamChunk.CompactionStarted? = null,
        val compactionInfo: StreamChunk.Compaction? = null,
        val compactionCompletedInfo: StreamChunk.CompactionCompleted? = null
    )

internal data class BuildPromptResult(
        val messages: MutableList<ModelMessage>,
        val systemPromptText: String = "",
        val compactionInfo: StreamChunk.Compaction? = null,
        val compactionStartedInfo: StreamChunk.CompactionStarted? = null,
        val compactionCompletedInfo: StreamChunk.CompactionCompleted? = null
    )

internal fun Agent.buildMetaJson(existingMeta: Map<String, String>?, thinkingDurationMs: Long): String {
        if (thinkingDurationMs <= 0L) return ""
        if (existingMeta.isNullOrEmpty()) {
            return "{\"thinkingDurationMs\":$thinkingDurationMs}"
        }
        val sb = sbPool.acquire()
        try {
            sb.append('{')
            existingMeta.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(',')
                sb.append('"').append(k).append("\":\"").append(v).append('"')
            }
            sb.append(",\"thinkingDurationMs\":").append(thinkingDurationMs)
            sb.append('}')
            return sb.toString()
        } finally {
            sbPool.release(sb)
        }
    }

internal suspend fun Agent.prepareMessageContext(
        sessionId: String,
        channelId: String,
        userMessage: String,
        overrideProviderId: String?,
        skipUserMessage: Boolean = false,
        systemPromptSuffix: String? = null,
        overrideModel: String? = null,
        forceEscalate: Boolean = false,
    ): PreparedContext? {
        if (!networkMonitor.isOnline()) {
            val queued = com.lin.hippyagent.core.network.QueuedMessage(
                sessionId = sessionId,
                content = userMessage,
                channelId = profile.agentId
            )
            offlineMessageQueue.enqueue(queued)
            Timber.w("Network offline: message queued (id=${queued.id})")
            return null
        }

        val effectiveClient = resolveModelClient(overrideProviderId)

        if (!skipUserMessage) {
            val addResult = sessionStore.addMessage(sessionId, MessageRole.USER, userMessage)
            Timber.d("prepareMessageContext[$sessionId]: user msg added, result=${addResult.isSuccess}, content=${userMessage.take(80)}, error=${addResult.exceptionOrNull()?.message}")
        }

        val messageCount = sessionStore.getMessages(sessionId, includeCompressed = false)
            .getOrDefault(emptyList()).size
        if (contextManager.quickEstimateNeedsCompression(messageCount, modelContextWindow = resolveModelContextWindow())) {
            Timber.d("Preemptive compression: estimated $messageCount messages may exceed context window")
        }

        // 升级到 heavy model 的两种独立来源:
        // 1. escalatedByDecision: ModeOrchestrator 经 LLM 判定本 turn 需要复杂模型
        // 2. escalatedByUser:     用户显式输入 /pro 命令
        val escalatedByDecision = forceEscalate
        val escalatedByUser = userMessage.trim().equals("/pro", ignoreCase = true)
        val escalatedThisTurn = escalatedByDecision || escalatedByUser
        if (escalatedThisTurn) {
            Timber.i("Escalate heavy model: byDecision=$escalatedByDecision, byUser=$escalatedByUser")
        }

        val effectiveModel = overrideModel ?: profile.modelName
        val promptResult = buildPrompt(sessionId, systemPromptSuffix = systemPromptSuffix, isEscalated = escalatedThisTurn, effectiveModelName = effectiveModel)
        val messages = promptResult.messages

        val fullToolDefinitions = toolRegistry.getDefinitionsForAgent(
            agentId = profile.agentId
        ).map { def ->
            com.lin.hippyagent.core.model.ModelToolDefinition(
                name = def.name,
                description = def.description,
                parameters = Agent.buildToolParameterSchema(def.parameters)
            )
        }

        // T1-1 LlmToolRouter：首轮路由裁剪工具子集，缓存到 PreparedContext 供本轮流式/非流式两处复用
        val toolDefinitions = if (profile.running.llmToolRouterEnabled && fullToolDefinitions.isNotEmpty()) {
            val routed = llmToolRouter.routeTools(
                userMessage = userMessage,
                availableTools = fullToolDefinitions,
                routingClient = effectiveClient,
                modelName = profile.modelName
            )
            Timber.d("LlmToolRouter: source=${routed.source}, selected=${routed.selectedTools.size}/${fullToolDefinitions.size}")
            routed.selectedTools
        } else {
            fullToolDefinitions
        }

        return PreparedContext(
            effectiveClient = effectiveClient,
            messages = messages,
            toolDefinitions = toolDefinitions,
            escalatedThisTurn = escalatedThisTurn,
            compactionStartedInfo = promptResult.compactionStartedInfo,
            compactionInfo = promptResult.compactionInfo,
            compactionCompletedInfo = promptResult.compactionCompletedInfo
        )
    }

internal suspend fun Agent.buildPrompt(sessionId: String, planContext: String? = null, systemPromptSuffix: String? = null, isEscalated: Boolean = false, effectiveModelName: String? = null): BuildPromptResult {
        val messages = mutableListOf<ModelMessage>()
        var compactionInfo: StreamChunk.Compaction? = null
        var compactionStartedInfo: StreamChunk.CompactionStarted? = null
        var compactionCompletedInfo: StreamChunk.CompactionCompleted? = null

        val workingDir = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
        workingDir.mkdirs()

        // 仅在全新工作区（无任何核心文件）且未完成引导时创建 BOOTSTRAP.md
        // 避免已删除 BOOTSTRAP.md 的成熟工作区被重新触发引导
        val bootstrapFile = java.io.File(workingDir, "BOOTSTRAP.md")
        val bootstrapCompletedMarker = java.io.File(workingDir, ".bootstrap_completed")
        val hasExistingCoreFiles = java.io.File(workingDir, "PROFILE.md").exists() ||
                java.io.File(workingDir, "SOUL.md").exists() ||
                java.io.File(workingDir, "RULES.md").exists()
        if (!bootstrapFile.exists() && !bootstrapCompletedMarker.exists() && !hasExistingCoreFiles) {
            try {
                context.assets.open("templates/BOOTSTRAP.md").use { input ->
                    bootstrapFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.i("BOOTSTRAP.md copied from assets to ${workingDir.absolutePath}")
            } catch (e: Exception) {
                Timber.w(e, "Failed to copy BOOTSTRAP.md from assets, creating default")
                bootstrapFile.writeText(
                    """# 启动引导

_你刚醒来。该搞清楚自己是谁了。_

还没有记忆。这是全新的工作区，记忆文件在你创建之前不存在很正常。

## 对话

像这样开始：

> "嘿，我刚上线。我是谁？你是谁？"

然后一起搞清楚：

1. **你的名字** — 他们该怎么叫你？
2. **你的定位** — 你是什么？
3. **你的风格** — 正式？随意？调皮？温暖？

## 完成后

确保以上的内容都保存到文件后。删除这个文件（`BOOTSTRAP.md`）。你不再需要引导脚本了 — 你已经是你了。
"""
                )
            }
        }

        ensureBootstrapHook(workingDir)

        val allMessages = sessionStore.getMessages(sessionId, includeCompressed = false).getOrDefault(emptyList())
        val afterClearMarker = allMessages.indexOfLast { it.role == MessageRole.SYSTEM && it.content == com.lin.hippyagent.core.command.ClearCommandHandler.CONTEXT_CLEARED_MARKER }
        val messagesAfterClear = if (afterClearMarker >= 0) allMessages.drop(afterClearMarker + 1) else allMessages
        val sessionMessages = contextMessageFilter?.let { filter -> messagesAfterClear.filter { filter(it) } } ?: messagesAfterClear

        val autoMemorySearchConfig = profile.running.remeLightMemoryConfig.autoMemorySearchConfig
        val memTraceCtx = coroutineContext[TraceContextElement]
        val memSpan = if (memTraceCtx != null) {
            SpanCollector.startSpan(
                type = SpanType.MEMORY_RETRIEVAL,
                traceId = memTraceCtx.traceId,
                parentSpanId = memTraceCtx.parentSpanId,
                props = mapOf(
                    "query" to (sessionMessages.lastOrNull { it.role == MessageRole.USER }?.content?.take(200) ?: ""),
                    "retrieverType" to "hybrid"
                )
            )
        } else {
            SpanContext.NoOp
        }
        val memStartedAt = System.currentTimeMillis()
        val memResult = runCatching {
            if (commonMemoryRepo != null && sessionMessages.isNotEmpty() && autoMemorySearchConfig.enabled) {
                val lastUserMsg = sessionMessages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
                if (lastUserMsg.isNotBlank()) {
                    if (autoMemorySearchConfig.enhancedSearchEnabled) {
                        val intent = QueryIntentClassifier.classify(lastUserMsg).toSearchIntent()
                        commonMemoryRepo.search(lastUserMsg, profile.agentId, intent = intent, limit = autoMemorySearchConfig.maxResults)
                    } else {
                        commonMemoryRepo.searchHybridByAgentId(lastUserMsg, profile.agentId, limit = autoMemorySearchConfig.maxResults)
                    }.filter { it.second >= autoMemorySearchConfig.minScore }
                        .sortedByDescending { it.second }
                        .take(autoMemorySearchConfig.maxResults)
                } else emptyList()
            } else emptyList()
        }
        val commonMemoryEntries = memResult.getOrDefault(emptyList())
        SpanCollector.end(
            memSpan,
            error = memResult.exceptionOrNull()?.message,
            extraProps = mapOf(
                "results" to commonMemoryEntries.size,
                "retrievalTimeMs" to (System.currentTimeMillis() - memStartedAt)
            )
        )

        // T2-2 Volunteer 主动注入
        val volunteeredMemories = runCatching {
            if (volunteerContextInjector != null && sessionMessages.isNotEmpty()) {
                val window = sessionMessages.takeLast(10).map { msg ->
                    com.lin.hippyagent.core.memory.volunteer.WindowTurn(
                        role = if (msg.role == MessageRole.USER) "user" else "assistant",
                        text = msg.content,
                        timestamp = msg.timestamp.toEpochMilli()
                    )
                }
                volunteerContextInjector.volunteer(window)
            } else emptyList()
        }.getOrDefault(emptyList())

        val promptContext = PromptContext(
            workingDir = workingDir,
            agentId = profile.agentId,
            sessionId = sessionId,
            coreFiles = profile.coreFiles,
            globalRules = configStorage?.getString("global_rules")?.takeIf { it.isNotBlank() },
            commonMemoryEntries = commonMemoryEntries,
            volunteeredMemories = volunteeredMemories,
            skills = buildSkillInfoList(workingDir),
            resolvedSkills = resolveTriggeredSkills(sessionMessages),
            skillCatalogText = if (profile.skills.isNotEmpty()) skillCatalog.buildProgressiveCatalogText(profile.skills) else null,
            progressiveSkillLoading = true,
            deferredToolNames = toolRegistry.getDeferredToolNames(),
            planContext = planContext,
            appAliases = com.lin.hippyagent.core.tools.android.AppPackageResolver.getAliasMap()
        )

        val systemPrompt = promptBuilder.buildSystemPrompt(promptContext)
        val escalationSuffix = if (profile.complexModelName.isNotEmpty()) {
            "\n\n" + com.lin.hippyagent.core.model.routing.EscalationContract.getContract(
                isHeavyModel = isEscalated
            )
        } else ""
        val effectiveSystemPrompt = buildString {
            append(systemPrompt)
            if (!systemPromptSuffix.isNullOrBlank()) {
                append("\n\n")
                append(systemPromptSuffix)
            }
            append(escalationSuffix)
        }

        Timber.d("buildPrompt[$sessionId]: bootstrap=${bootstrapHook.isBootstrapMode()}, sessionMsgCount=${sessionMessages.size}, " +
                "msgRoles=${sessionMessages.map { it.role }.joinToString()}")

        if (bootstrapHook.isBootstrapMode()) {
            if (sessionMessages.isEmpty()) {
                messages.add(ModelMessage(role = "system", content = effectiveSystemPrompt + bootstrapHook.getSystemPromptAddition()))
                Timber.d("buildPrompt[$sessionId]: bootstrap mode + empty msgs → injecting bootstrap addition")
            } else {
                messages.add(ModelMessage(role = "system", content = effectiveSystemPrompt))
                Timber.d("buildPrompt[$sessionId]: bootstrap mode but msgs exist → system prompt only")
            }
        } else {
            messages.add(ModelMessage(role = "system", content = effectiveSystemPrompt))
        }

        val checkResult = contextManager.checkContext(
            sessionMessages,
            systemPrompt,
            modelContextWindow = resolveModelContextWindow()
        )

        _contextTokenInfo.value = ContextTokenInfo(
            currentTokens = checkResult.totalTokens.toLong(),
            maxTokens = checkResult.maxTokens.toLong()
        )

        val existingSummary = sessionStore.getCompressedSummary(sessionId).getOrNull()

        if (existingSummary != null) {
            messages.add(ModelMessage(role = "system", content = SUMMARY_PREFIX + existingSummary))
        }

        if (checkResult.needsCompression && checkResult.messagesToCompress.isNotEmpty()
            && contextManager.useCompression) {
            Timber.d("Context compression triggered: ${checkResult.totalTokens} tokens, " +
                    "compressing ${checkResult.messagesToCompress.size} messages")

            // 记录压缩开始信息
            compactionStartedInfo = StreamChunk.CompactionStarted(
                totalTokens = checkResult.totalTokens,
                maxTokens = checkResult.maxTokens,
                messagesToCompress = checkResult.messagesToCompress.size,
                messagesToKeep = checkResult.messagesToKeep.size
            )
            // 兼容旧字段
            compactionInfo = StreamChunk.Compaction(checkResult.messagesToCompress.size, 0)

            val compactionPrompt = contextManager.buildCompactionPrompt(checkResult.messagesToCompress)
            val newSummary = performLlmCompaction(compactionPrompt, existingSummary, checkResult.messagesToCompress)

            sessionStore.updateCompressedSummary(sessionId, newSummary)
            val compressedIds = checkResult.messagesToCompress.map { it.id }
            sessionStore.markMessagesCompressed(compressedIds)

            val keptTokens = contextManager.getTokenStats(checkResult.messagesToKeep)["total"] ?: 0
            val summaryTokenEstimate = (SUMMARY_PREFIX.length + newSummary.length) / 4
            val systemTokenEstimate = systemPrompt.length / 4
            val postCompactionTokens = (keptTokens + summaryTokenEstimate + systemTokenEstimate).toLong()
            _contextTokenInfo.value = ContextTokenInfo(
                currentTokens = postCompactionTokens,
                maxTokens = checkResult.maxTokens.toLong()
            )

            Timber.d("Context compressed, summary length: ${newSummary.length} chars, marked ${compressedIds.size} messages as compressed")
            compactionInfo = StreamChunk.Compaction(compressedIds.size, newSummary.length)
            compactionCompletedInfo = StreamChunk.CompactionCompleted(
                compressedCount = compressedIds.size,
                newTokenEstimate = checkResult.messagesToKeep.sumOf { contextManager.getTokenStats(listOf(it))["total"] ?: 0 } + (contextManager.getTokenStats(checkResult.messagesToCompress)["total"] ?: 0) / 4,
                maxTokens = checkResult.maxTokens,
                beforeTokens = checkResult.totalTokens
            )
        }

        val messagesToAdd = if (checkResult.needsCompression) {
            checkResult.messagesToKeep
        } else {
            checkResult.prunedMessages
        }

        val lastUserMsgIndex = messagesToAdd.indexOfLast { it.role == MessageRole.USER }

        val imageExtensions = Agent.imageExtensions
        val attachmentRegex = Agent.attachmentRegex

        messagesToAdd.forEachIndexed { idx, sessionMessage ->
            val role = when (sessionMessage.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
                MessageRole.TOOL -> "tool"
                MessageRole.PRIVATE -> "user"
            }
            // 保留 toolCalls 和 toolCallId，避免 LLM API 因缺少 tool_call_id 拒绝请求
            val modelMessage = if (sessionMessage.role == MessageRole.ASSISTANT && sessionMessage.toolCalls.isNotEmpty()) {
                ModelMessage(
                    role = role,
                    content = sessionMessage.content,
                    toolCalls = sessionMessage.toolCalls.map { tc ->
                        ToolCallInfo(
                            id = tc.id,
                            function = FunctionInfo(
                                name = tc.name,
                                arguments = tc.arguments
                            )
                        )
                    }
                )
            } else if (sessionMessage.role == MessageRole.TOOL && sessionMessage.toolName != null) {
                // TOOL 角色消息需要 tool_call_id 以匹配 ASSISTANT 的 tool_calls
                // 尝试从 toolCalls 列表中获取 id，或用 toolName 作为回退标识
                ModelMessage(
                    role = role,
                    content = sessionMessage.content,
                    toolCallId = sessionMessage.toolCalls.firstOrNull()?.id ?: sessionMessage.id
                )
            } else {
                val attachments = Agent.attachmentRegex.findAll(sessionMessage.content).map { it.groupValues[1] }.toList()
                val imageFiles = attachments.filter { ext ->
                    ext.substringAfterLast(".", "").lowercase() in Agent.imageExtensions
                }
                val nonImageAttachments = attachments.filter { ext ->
                    ext.substringAfterLast(".", "").lowercase() !in Agent.imageExtensions
                }
                val isLatestUserMsg = role == "user" && idx == lastUserMsgIndex
                var textWithoutAttachments = if (attachments.isNotEmpty()) Agent.attachmentRegex.replace(sessionMessage.content, "").trim() else sessionMessage.content

                if (role == "user" && sessionMessage.metadataJson != null) {
                    val quotedPrefix = buildQuotedMessagePrefix(sessionMessage.metadataJson)
                    if (quotedPrefix != null) {
                        textWithoutAttachments = "$quotedPrefix\n$textWithoutAttachments"
                    }
                }

                if (imageFiles.isNotEmpty() && role == "user") {
                    val modelSupportsVision = effectiveModelName != null && Agent.MODEL_VISION_REGEX.containsMatchIn(effectiveModelName)
                    if (isLatestUserMsg && modelSupportsVision) {
                        val blocks = mutableListOf<com.lin.hippyagent.core.model.ContentBlock>()
                        if (textWithoutAttachments.isNotBlank()) {
                            blocks.add(com.lin.hippyagent.core.model.ContentBlock.Text(textWithoutAttachments))
                        }
                        imageFiles.forEach { path ->
                            try {
                                val file = java.io.File(path)
                                if (file.exists() && file.length() < 5 * 1024 * 1024) {
                                    val bytes = file.readBytes()
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    val mimeType = when (file.extension.lowercase()) {
                                        "png" -> "image/png"
                                        "jpg", "jpeg" -> "image/jpeg"
                                        "gif" -> "image/gif"
                                        "webp" -> "image/webp"
                                        else -> "image/png"
                                    }
                                    blocks.add(com.lin.hippyagent.core.model.ContentBlock.ImageUrl(
                                        com.lin.hippyagent.core.model.ImageUrlDetail("data:$mimeType;base64,$base64")
                                    ))
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to load image: $path")
                            }
                        }
                        nonImageAttachments.forEach { path ->
                            blocks.add(com.lin.hippyagent.core.model.ContentBlock.Text("[用户发送了附件: $path，如需查看请使用 read_file 工具]"))
                        }
                        val hasImage = blocks.any { it is com.lin.hippyagent.core.model.ContentBlock.ImageUrl }
                        if (hasImage) {
                            ModelMessage(role = role, content = textWithoutAttachments, contentBlocks = blocks)
                        } else {
                            val fallback = blocks.filterIsInstance<com.lin.hippyagent.core.model.ContentBlock.Text>().joinToString("\n") { (it as com.lin.hippyagent.core.model.ContentBlock.Text).text }
                            ModelMessage(role = role, content = fallback.ifBlank { sessionMessage.content })
                        }
                    } else {
                        val guidance = buildString {
                            append(textWithoutAttachments)
                            if (isNotEmpty() && textWithoutAttachments.isNotBlank()) append("\n")
                            if (isLatestUserMsg && effectiveModelName != null && !Agent.MODEL_VISION_REGEX.containsMatchIn(effectiveModelName)) {
                                append("[当前模型($effectiveModelName)不支持图片理解，图片已忽略。如需分析图片，请切换到支持视觉的模型]\n")
                            }
                            imageFiles.forEach { path ->
                                append("[用户发送了图片: $path，如需查看请使用 view_image 工具]\n")
                            }
                            nonImageAttachments.forEach { path ->
                                append("[用户发送了附件: $path，如需查看请使用 read_file 工具]\n")
                            }
                        }.trimEnd()
                        ModelMessage(role = role, content = guidance)
                    }
                } else if (nonImageAttachments.isNotEmpty() && role == "user") {
                    val guidance = buildString {
                        append(textWithoutAttachments)
                        if (isNotEmpty() && textWithoutAttachments.isNotBlank()) append("\n")
                        nonImageAttachments.forEach { path ->
                            append("[用户发送了附件: $path，如需查看请使用 read_file 工具]\n")
                        }
                    }.trimEnd()
                    ModelMessage(role = role, content = guidance)
                } else {
                    ModelMessage(role = role, content = textWithoutAttachments)
                }
            }
            messages.add(modelMessage)
        }

        Timber.d("buildPrompt[$sessionId]: final prompt has ${messages.size} messages: ${messages.map { it.role }.joinToString()}")
        return BuildPromptResult(messages, effectiveSystemPrompt, compactionInfo, compactionStartedInfo, compactionCompletedInfo)
    }

internal fun Agent.buildQuotedMessagePrefix(metadataJson: String): String? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(metadataJson) as? kotlinx.serialization.json.JsonObject ?: return null
            val quotedContent = obj["quotedContent"]?.jsonPrimitive?.content ?: return null
            val quotedSenderName = obj["quotedSenderName"]?.jsonPrimitive?.content
            val senderLabel = quotedSenderName?.ifBlank { null } ?: "某条消息"
            "[用户引用了${senderLabel}的消息: ${quotedContent.take(200)}]"
        } catch (_: Exception) { null }
    }

internal suspend fun Agent.resolveModelContextWindow(): Int? {
        val store = modelProviderStore ?: return null
        val modelName = stripModelPrefix(profile.modelName)
        try {
            val providers = store.providers.first()
            for (provider in providers) {
                val match = provider.models.find {
                    stripModelPrefix(it.name) == modelName || it.name == modelName
                }
                if (match?.contextWindow != null) {
                    val guardResult = ContextWindowGuard.check(match.contextWindow)
                    ContextWindowGuard.warnIfNecessary(guardResult, profile.modelName)
                    return guardResult.effectiveContextWindow.takeIf {
                        guardResult.decision != ContextWindowGuard.GuardDecision.BLOCK
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Agent: resolveModelContextWindow failed, fallback to default")
        }
        return null
    }

/**
     * 从 ModelProviderStore 查找当前模型的自定义 maxTokens 设置
     * 如果模型配置中设置了 maxTokens，优先使用（覆盖 RunningConfig 的默认值）
     */
internal suspend fun Agent.resolveModelMaxTokens(): Int? {
        val store = modelProviderStore ?: return null
        val modelName = stripModelPrefix(profile.modelName)
        try {
            val providers = store.providers.first()
            for (provider in providers) {
                val match = provider.models.find {
                    stripModelPrefix(it.name) == modelName || it.name == modelName
                }
                if (match?.maxTokens != null) {
                    return match.maxTokens
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Agent: resolveModelMaxTokens failed, fallback to default")
        }
        return null
    }

/**
     * 构建智能体已启用技能的信息列表，用于注入系统提示词
     */
internal fun Agent.buildSkillInfoList(workingDir: java.io.File): List<com.lin.hippyagent.core.prompt.SkillInfo> {
        val skillIds = profile.skills
        if (skillIds.isEmpty()) return emptyList()

        val globalSkillsDir = java.io.File(storageManager.getWorkingDir(), "skills")
        val agentSkillsDir = java.io.File(workingDir, "skills")
        val result = mutableListOf<com.lin.hippyagent.core.prompt.SkillInfo>()

        for (skillId in skillIds) {
            // 优先查找智能体专属技能目录，然后查找全局技能池
            val agentSkillDir = java.io.File(agentSkillsDir, skillId)
            val globalSkillDir = java.io.File(globalSkillsDir, skillId)
            val skillDir = when {
                agentSkillDir.exists() -> agentSkillDir
                globalSkillDir.exists() -> globalSkillDir
                else -> null
            }

            if (skillDir != null) {
                val skillMd = java.io.File(skillDir, "SKILL.md")
                val skillMdPath = if (skillMd.exists()) skillMd.absolutePath else ""
                // 从 SKILL.md 的前 5 行提取简要描述
                val description = if (skillMd.exists()) {
                    try {
                        skillMd.readLines().take(5)
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .firstOrNull()?.take(120) ?: ""
                    } catch (_: Exception) { "" }
                } else ""
                result.add(com.lin.hippyagent.core.prompt.SkillInfo(
                    id = skillId,
                    name = skillId.replace("-", " ").replaceFirstChar { it.uppercase() },
                    description = description,
                    skillFilePath = skillMdPath
                ))
            } else {
                // 技能目录不存在，仅列出 ID
                result.add(com.lin.hippyagent.core.prompt.SkillInfo(
                    id = skillId,
                    name = skillId.replace("-", " ").replaceFirstChar { it.uppercase() }
                ))
            }
        }
        return result
    }

internal suspend fun Agent.resolveTriggeredSkills(sessionMessages: List<SessionMessage>): List<com.lin.hippyagent.core.skill.ResolvedSkill> {
        val skillIds = profile.skills
        if (skillIds.isEmpty()) return emptyList()
        val lastUserMsg = sessionMessages.lastOrNull { it.role == MessageRole.USER }?.content ?: return emptyList()

        val traceCtx = coroutineContext[TraceContextElement]
        val span = if (traceCtx != null) {
            SpanCollector.startSpan(
                type = SpanType.SKILL_MATCH,
                traceId = traceCtx.traceId,
                parentSpanId = traceCtx.parentSpanId,
                props = mapOf(
                    "userGoal" to lastUserMsg.take(200)
                )
            )
        } else {
            SpanContext.NoOp
        }
        val result = runCatching {
            skillTriggerResolver.resolve(lastUserMsg, skillIds)
        }
        val resolved = result.getOrElse { emptyList() }
        SpanCollector.end(
            span,
            error = result.exceptionOrNull()?.message,
            extraProps = mapOf(
                "candidates" to resolved.map { it.name },
                "winner" to (resolved.firstOrNull()?.name ?: "none")
            )
        )
        return resolved
    }

internal fun Agent.triggerMemoryExtraction(sessionId: String) {
        val extractor = memoryExtractor ?: return
        memoryExtractionScope.launch {
            runCatching {
                val messages = sessionStore.getMessages(sessionId, includeCompressed = false).getOrNull() ?: return@runCatching
                val conversation = messages.takeLast(20).map { msg ->
                    msg.role.name.lowercase() to msg.content
                }
                if (conversation.size >= 2) {
                    extractor.extractAndStoreAsync(conversation)
                }
            }.onFailure { e ->
                Timber.w(e, "MemoryExtractor: trigger failed for session $sessionId")
            }
        }
    }
