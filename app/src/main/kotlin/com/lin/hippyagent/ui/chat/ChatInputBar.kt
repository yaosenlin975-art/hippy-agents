package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.skill.SkillInfo
import timber.log.Timber

private sealed class SlashItem {
    abstract val label: String
    abstract val description: String
    data class Command(override val label: String, override val description: String) : SlashItem()
    data class Skill(override val label: String, override val description: String) : SlashItem()
}

@Immutable
data class ChatInputUiState(
    val value: String = "",
    val enabled: Boolean = true,
    val isAgentThinking: Boolean = false,
    val queueSize: Int = 0,
    val chips: List<InputChip> = emptyList(),
    val agentSkills: List<SkillInfo> = emptyList(),
    val isSttAvailable: Boolean = false,
    val isSttListening: Boolean = false,
    val sttPartialResult: String? = null,
    val sttEngineLabel: String? = null,
    val isGroupChat: Boolean = false,
    val groupMembers: Map<String, String> = emptyMap(),
    val quotedMessage: QuotedMessage? = null,
    val isMultiSelectMode: Boolean = false,
    val selectedCount: Int = 0,
    val isRecordingVoice: Boolean = false,
    val recordingDurationMs: Long = 0L
)

interface ChatInputCallbacks {
    fun onValueChange(value: String)
    fun onSend()
    fun onAttachImage()
    fun onAttachFile()
    fun onTakePicture()
    fun onAddChip(chip: InputChip)
    fun onRemoveChip(chipId: String)
    fun onStartStt()
    fun onStopStt()
    fun onRemoveQuote()
    fun onForwardSelected() {}
    fun onExportSelected() {}
    fun onDeleteSelected() {}
    fun onExitMultiSelect() {}
    fun onStartVoiceRecording() {}
    fun onStopVoiceRecording() {}
}

private val FILE_CHIP_COLORS    get() = ChipColors(container = Color(0xFFE3F2FD), content = Color(0xFF1565C0), icon = Color(0xFF1976D2))
private val IMAGE_CHIP_COLORS   get() = ChipColors(container = Color(0xFFE8F5E9), content = Color(0xFF2E7D32), icon = Color(0xFF388E3C))
private val SKILL_CHIP_COLORS   get() = ChipColors(container = Color(0xFFF3E5F5), content = Color(0xFF7B1FA2), icon = Color(0xFF8E24AA))
private val MENTION_CHIP_COLORS get() = ChipColors(container = Color(0xFFFFF8E1), content = Color(0xFFF57F17), icon = Color(0xFFFFA000))

// 退格整段删除模式：@mention / [附件: xxx] / /skill
private val WHOLE_DELETE_PATTERN = Regex("(?:@[\\w\\u4e00-\\u9fff-]+ |\\[附件: .+?\\] ?|/[^\\s/]+ )$")
private val ATTACHMENT_LABEL_PATTERN = Regex("\\[附件: (.+?)\\]")

private data class ChipColors(val container: Color, val content: Color, val icon: Color)

private fun chipColorsForType(type: InputChipType) = when (type) {
    InputChipType.FILE    -> FILE_CHIP_COLORS
    InputChipType.IMAGE   -> IMAGE_CHIP_COLORS
    InputChipType.SKILL   -> SKILL_CHIP_COLORS
    InputChipType.MENTION -> MENTION_CHIP_COLORS
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatInputBar(
    state: ChatInputUiState,
    callbacks: ChatInputCallbacks,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showMentionPicker by remember { mutableStateOf(false) }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = state.value, selection = TextRange(state.value.length)))
    }
    LaunchedEffect(state.value) {
        val current = textFieldValue.text
        if (state.value != current) {
            textFieldValue = TextFieldValue(text = state.value, selection = TextRange(state.value.length))
        }
    }

    val mentionRegex = remember { Regex("@([\\w\\u4e00-\\u9fff-]*)$") }
    val mentionMatch = mentionRegex.find(state.value)
    val isTypingMention = state.isGroupChat && mentionMatch != null
    val mentionFilter = mentionMatch?.groupValues?.get(1) ?: ""

    val slashRegex = remember { Regex("/([\\w-]*)$") }
    val slashMatch = slashRegex.find(state.value)
    val isTypingSlash = slashMatch != null && state.value.count { it == '/' } <= 1
    val slashFilter = slashMatch?.groupValues?.get(1)?.lowercase() ?: ""

    val slashCommands = remember {
        listOf(
            "/compact" to "压缩上下文",
            "/new" to "创建新会话",
            "/clear" to "清除消息",
            "/history" to "查看历史",
            "/mission" to "启动任务模式",
            "/plan" to "生成任务计划",
            "/proactive" to "主动记忆开关",
            "/stats" to "查看统计",
            "/backup" to "备份管理",
            "/summarize_status" to "总结状态",
        )
    }
    val filteredSlashItems = remember(isTypingSlash, slashFilter, state.agentSkills) {
        if (!isTypingSlash) emptyList()
        else {
            val cmdMatches = slashCommands.filter { (cmd, _) ->
                if (slashFilter.isEmpty()) true else cmd.drop(1).startsWith(slashFilter)
            }
            val skillMatches = state.agentSkills.map { it.id to it.name }.filter { (id, _) ->
                if (slashFilter.isEmpty()) true else id.startsWith(slashFilter)
            }
            cmdMatches.map { SlashItem.Command(it.first, it.second) } +
                skillMatches.map { SlashItem.Skill(it.first, it.second) }
        }
    }
    val showSlashDropdown = isTypingSlash && filteredSlashItems.isNotEmpty()

    val filteredMembers = remember(showMentionPicker, isTypingMention, mentionFilter, state.groupMembers) {
        if (showMentionPicker) {
            if (mentionFilter.isEmpty()) state.groupMembers
            else state.groupMembers.filter { (key, name) -> name.contains(mentionFilter, ignoreCase = true) || key.contains(mentionFilter, ignoreCase = true) }
        } else if (!isTypingMention) emptyMap()
        else if (mentionFilter.isEmpty()) state.groupMembers
        else state.groupMembers.filter { (key, name) -> name.contains(mentionFilter, ignoreCase = true) || key.contains(mentionFilter, ignoreCase = true) }
    }

    val showMentionDropdown = state.isGroupChat && (showMentionPicker || (isTypingMention && filteredMembers.isNotEmpty()))

    if (showSkillPicker) {
        AlertDialog(
            onDismissRequest = { showSkillPicker = false },
            title = { Text("选择技能", fontWeight = FontWeight.Bold) },
            text = {
                if (state.agentSkills.isEmpty()) {
                    Text("当前智能体暂无可用技能", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        items(state.agentSkills, key = { it.id }) { skill ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                onClick = {
                                    val updatedValue = if (state.value.isBlank()) "/${skill.name} "
                                    else if (state.value.endsWith(' ')) "${state.value}/${skill.name} "
                                    else "${state.value} /${skill.name} "
                                    callbacks.onValueChange(updatedValue)
                                    showSkillPicker = false
                                    focusRequester.requestFocus()
                                }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(skill.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    if (skill.description.isNotEmpty()) {
                                        Text(
                                            text = skill.description,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSkillPicker = false }) { Text("关闭") }
            }
        )
    }

    Box(modifier = Modifier) {
        Column(modifier = modifier.fillMaxWidth()) {
        if (state.quotedMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.quotedMessage.senderName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = state.quotedMessage.content,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { callbacks.onRemoveQuote() },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "取消引用",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (state.isMultiSelectMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { callbacks.onExitMultiSelect() }) {
                    Text("取消")
                }
                Text("${state.selectedCount} 条已选", style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { callbacks.onForwardSelected() }) {
                        Text("转发")
                    }
                    TextButton(onClick = { callbacks.onExportSelected() }) {
                        Text("导出")
                    }
                    TextButton(onClick = { callbacks.onDeleteSelected() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("删除")
                    }
                }
            }
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { newTfv ->
                    val newValue = newTfv.text
                    if (newValue.length < state.value.length) {
                        // 退格时检测旧文本末尾是否有整段模式需要扩展删除
                        val oldValue = state.value
                        val match = WHOLE_DELETE_PATTERN.find(oldValue)
                        if (match != null) {
                            val expanded = oldValue.removeRange(match.range)
                            // 同步删除对应的附件 chip
                            val attachmentMatch = ATTACHMENT_LABEL_PATTERN.find(match.value)
                            if (attachmentMatch != null) {
                                val chipLabel = attachmentMatch.groupValues[1]
                                state.chips.find { it.type == InputChipType.FILE || it.type == InputChipType.IMAGE }
                                    ?.takeIf { it.label == chipLabel }
                                    ?.let { callbacks.onRemoveChip(it.id) }
                            }
                            if (expanded != newValue) {
                                textFieldValue = TextFieldValue(text = expanded, selection = TextRange(expanded.length))
                                callbacks.onValueChange(expanded)
                                return@OutlinedTextField
                            }
                        }
                    }
                    textFieldValue = newTfv
                    callbacks.onValueChange(newValue)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        if (state.isSttListening) "正在聆听..."
                        else if (state.isAgentThinking && state.queueSize > 0) "排队中 (${state.queueSize})..."
                        else if (state.isAgentThinking) "智能体思考中，仍可输入..."
                        else "输入消息..."
                    )
                },
                maxLines = 4,
                enabled = true,
                shape = RoundedCornerShape(24.dp),
                prefix = null,
                leadingIcon = {
                    Box {
                        var showAttachMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showAttachMenu = true },
                            enabled = state.enabled,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "附件",
                                modifier = Modifier.size(18.dp),
                                tint = if (state.chips.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else if (state.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("选择图片") },
                                onClick = {
                                    showAttachMenu = false
                                    callbacks.onAttachImage()
                                },
                                leadingIcon = { Icon(Icons.Default.Image, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("拍照") },
                                onClick = {
                                    showAttachMenu = false
                                    callbacks.onTakePicture()
                                },
                                leadingIcon = { Icon(Icons.Default.CameraAlt, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("选择文件") },
                                onClick = {
                                    showAttachMenu = false
                                    callbacks.onAttachFile()
                                },
                                leadingIcon = { Icon(Icons.Default.AttachFile, null) }
                            )
                            if (!state.isGroupChat) {
                                DropdownMenuItem(
                                    text = { Text("技能") },
                                    onClick = {
                                        showAttachMenu = false
                                        showSkillPicker = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Extension, null) }
                                )
                            }
                        }
                    }
                },
                trailingIcon = {
                    Row(
                        modifier = Modifier.padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        if (state.isGroupChat && state.groupMembers.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    showMentionPicker = true
                                    focusRequester.requestFocus()
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AlternateEmail,
                                    contentDescription = "@提及智能体",
                                    modifier = Modifier.size(19.dp),
                                    tint = if (isTypingMention) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.isSttAvailable) {
                            Box(
                                modifier = Modifier
                                .size(34.dp)
                                .then(
                                    if (state.isSttListening) {
                                        Modifier.background(Color(0x33FF4444), CircleShape)
                                    } else if (state.isRecordingVoice) {
                                        Modifier.background(Color(0x33FF4444), CircleShape)
                                    } else Modifier
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (state.isRecordingVoice) return@combinedClickable
                                        if (state.isSttListening) callbacks.onStopStt() else callbacks.onStartStt()
                                    },
                                    onLongClick = {
                                        if (!state.isRecordingVoice) {
                                            callbacks.onStartVoiceRecording()
                                        }
                                    }
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.isRecordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = if (state.isRecordingVoice) "松开发送" else if (state.isSttListening) "停止聆听" else "语音输入",
                                    modifier = Modifier.size(19.dp),
                                    tint = if (state.isRecordingVoice) Color(0xFFFF4444)
                                    else if (state.isSttListening) Color(0xFFFF4444)
                                    else if (state.isSttAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                            }
                            if (state.isRecordingVoice) {
                                Text(
                                    text = "${state.recordingDurationMs / 1000}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF4444),
                                    modifier = Modifier.padding(start = 2.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                callbacks.onSend()
                                focusRequester.requestFocus()
                            },
                            enabled = state.value.isNotBlank() || state.chips.isNotEmpty(),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "发送",
                                modifier = Modifier.size(19.dp),
                                tint = if (state.value.isNotBlank() || state.chips.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )
        }
        if (showMentionDropdown && filteredMembers.isNotEmpty()) {
            val displayMembers = if (showMentionPicker) state.groupMembers else filteredMembers
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(displayMembers.entries.take(10).toList(), key = { it.key }) { (agentId, displayName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // @前后各添加一个空格，如: " @A "
                                    val insertText = " @$displayName "
                                    val updatedValue = if (mentionMatch != null) {
                                        state.value.replaceRange(mentionMatch.range, insertText)
                                    } else {
                                        val cursorPos = state.value.length
                                        buildString {
                                            append(state.value)
                                            if (cursorPos > 0 && !state.value.endsWith(' ')) append(' ')
                                            append(insertText)
                                        }
                                    }
                                    val cursorPos = if (mentionMatch != null) {
                                        mentionMatch.range.first + insertText.length
                                    } else {
                                        updatedValue.length
                                    }
                                    Timber.d("ChatInputBar: mention selected, replaced=%s, insert=%s, updatedValue=%s", mentionMatch?.value, insertText, updatedValue)
                                    textFieldValue = TextFieldValue(text = updatedValue, selection = TextRange(cursorPos))
                                    callbacks.onValueChange(updatedValue)
                                    // 不再添加 MENTION chip，只保留文本
                                    showMentionPicker = false
                                    // 请求焦点并将光标移到@后方
                                    focusRequester.requestFocus()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "@$displayName",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
        if (showSlashDropdown && filteredSlashItems.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(filteredSlashItems.take(10), key = { it.label }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val insertText = when (item) {
                                        is SlashItem.Command -> "${item.label} "
                                        is SlashItem.Skill -> "/${item.label} "
                                    }
                                    val updatedValue = if (slashMatch != null) {
                                        state.value.replaceRange(slashMatch.range, insertText)
                                    } else {
                                        buildString {
                                            append(state.value)
                                            append(insertText)
                                        }
                                    }
                                    val cursorPos = if (slashMatch != null) {
                                        slashMatch.range.first + insertText.length
                                    } else updatedValue.length
                                    textFieldValue = TextFieldValue(text = updatedValue, selection = TextRange(cursorPos))
                                    callbacks.onValueChange(updatedValue)
                                    if (item is SlashItem.Skill) {
                                        callbacks.onAddChip(InputChip(type = InputChipType.SKILL, label = item.label, id = "skill_${item.label}"))
                                    }
                                    focusRequester.requestFocus()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (item is SlashItem.Command) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = item.description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
}
