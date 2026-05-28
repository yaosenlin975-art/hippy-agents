package com.lin.hippyagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.ChatTurnConverter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ATTACHMENT_TAG_REGEX = Regex("""\[附件:\s*\S+\]""")

private fun formatMessageTime(timestamp: Instant): String {
    val zoned = timestamp.atZone(ZoneId.systemDefault())
    val localDate = zoned.toLocalDate()
    val now = java.time.LocalDate.now()
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    return when {
        localDate == now -> timeFormatter.format(zoned)
        localDate == now.minusDays(1) -> "昨天 ${timeFormatter.format(zoned)}"
        localDate.year == now.year -> DateTimeFormatter.ofPattern("M月d日 HH:mm").format(zoned)
        else -> DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm").format(zoned)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserTurnCard(
    turn: ChatTurn.UserTurn,
    onDelete: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onQuote: ((messageId: String, content: String, senderName: String) -> Unit)? = null,
    isGroupedWithPrevious: Boolean = false,
    onImageClick: ((String) -> Unit)? = null,
    agentReadStates: Map<String, String> = emptyMap(),
    agentProfiles: Map<String, String> = emptyMap(),
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onEnterMultiSelect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val content = turn.message.content
    val (thinkingContent, replyContent) = ChatTurnConverter.parseThinkingAndReply(content)
    var displayContent = replyContent.ifBlank { content }
    if (turn.originalImageUri != null) {
        displayContent = ATTACHMENT_TAG_REGEX.replace(displayContent, "").trim()
    }
    var showActions by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val bubbleOnClick: () -> Unit = if (isMultiSelectMode) onToggleSelection else ({})
    val bubbleOnLongClick: () -> Unit = if (!isMultiSelectMode) ({
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); showActions = true
    }) else ({})

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = remember(turn.message.timestamp) { formatMessageTime(turn.message.timestamp) },
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            textAlign = TextAlign.Center
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.wrapContentWidth(Alignment.End).widthIn(max = screenWidth * 0.8f)
        ) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
            if (turn.quotedContent != null) {
                Surface(
                    modifier = Modifier
                        .padding(bottom = 4.dp),
                    shape = RoundedCornerShape(8.dp, 8.dp, 0.dp, 0.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = turn.quotedSenderName ?: "引用消息",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = turn.quotedContent ?: "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .widthIn(max = screenWidth * 0.8f)
                    .then(
                        if (isMultiSelectMode && isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .clip(
                        RoundedCornerShape(
                            topStart = if (turn.quotedContent != null) 0.dp else 20.dp,
                            topEnd = if (turn.quotedContent != null) 0.dp else 20.dp,
                            bottomStart = 20.dp,
                            bottomEnd = 6.dp
                        )
                    )
                    .background(MaterialTheme.colorScheme.primary)
                    .combinedClickable(
                        onClick = bubbleOnClick,
                        onLongClick = bubbleOnLongClick
                    )
                    .padding(12.dp)
            ) {
                Column {
                    if (turn.originalImageUri != null) {
                        val imageModel: Any = if (turn.originalImageUri!!.startsWith("content://")) {
                            android.net.Uri.parse(turn.originalImageUri)
                        } else {
                            File(turn.originalImageUri!!)
                        }
                        AsyncImage(
                            model = imageModel,
                            contentDescription = "发送的图片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .then(
                                    if (onImageClick != null)
                                        Modifier.clickable {
                                            onImageClick(turn.originalImageUri!!)
                                        }
                                    else Modifier
                                ),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                    MessageContentWithAttachments(
                        content = displayContent,
                        isUser = true,
                        onImageClick = onImageClick,
                        agentProfiles = agentProfiles,
                        metadataJson = turn.message.metadataJson
                    )
                }
            }
            }
        }

        if (agentReadStates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp, top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                agentReadStates.forEach { (agentId, state) ->
                    val displayName = agentProfiles[agentId] ?: agentId
                    val (stateText, stateColor) = when (state) {
                        "已回复" -> "已回复" to MaterialTheme.colorScheme.primary
                        "工作中" -> "工作中" to MaterialTheme.colorScheme.tertiary
                        else -> "已读" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "$displayName $stateText",
                        fontSize = 10.sp,
                        color = stateColor,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }

    if (showActions) {
        MessageActionsSheet(
            isAgent = false,
            onCopy = {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", content))
                } catch (_: Exception) {}
            },
            onEdit = { showEditDialog = true },
            onQuote = onQuote?.let { cb ->
                {
                    cb(turn.message.id, displayContent.take(200), "你")
                }
            },
            onDelete = onDelete,
            onMultiSelect = onEnterMultiSelect?.let { cb -> { cb(turn.message.id) } },
            onDismiss = { showActions = false }
        )
    }

    if (showEditDialog) {
        var editText by remember { mutableStateOf(displayContent) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑消息") },
            text = {
                TextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editText.isNotBlank()) {
                            onEdit(editText)
                        }
                        showEditDialog = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消") }
            }
        )
    }
}
