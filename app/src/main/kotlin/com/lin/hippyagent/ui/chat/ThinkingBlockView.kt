package com.lin.hippyagent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.chat.ThinkingBlock

private const val STREAMING_CURSOR = "▎"
private const val SUMMARY_PREVIEW_MAX_CHARS = 50

private fun formatDuration(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}

@Composable
fun ThinkingBlockView(
    block: ThinkingBlock,
    isStreaming: Boolean = false,
    depth: Int = 0,
    modifier: Modifier = Modifier
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxHeight = screenHeight * 0.4f
    var expanded by remember { mutableStateOf(block.isExpanded) }
    var wasStreaming by remember { mutableStateOf(false) }
    var userManuallyExpanded by remember { mutableStateOf(false) }

    val collapseTrigger = LocalCollapseAll.current
    val expandTrigger = LocalExpandVisible.current

    LaunchedEffect(isStreaming) {
        if (isStreaming && !wasStreaming) {
            userManuallyExpanded = false
            expanded = true
        }
        if (!isStreaming && wasStreaming) {
            expanded = false
            userManuallyExpanded = false
        }
        wasStreaming = isStreaming
    }
    LaunchedEffect(collapseTrigger) {
        if (collapseTrigger > 0) {
            expanded = false
            userManuallyExpanded = false
        }
    }
    LaunchedEffect(expandTrigger) {
        if (expandTrigger > 0) {
            expanded = true
        }
    }

    val containerColor = if (depth == 0) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val startPadding = (depth * 12).dp
    val accentColor = if (isStreaming) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, bottom = 4.dp)
            .clickable {
                expanded = !expanded
                if (expanded) userManuallyExpanded = true
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(modifier = Modifier.padding(0.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(accentColor, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
            )
            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "▼" else "▶",
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = buildString {
                            append(if (depth == 0) stringResource(R.string.chat_thinking_process) else stringResource(R.string.chat_deep_thinking) + " · $depth")
                            if (!isStreaming && block.durationMs > 0) {
                                append(" · ${formatDuration(block.durationMs)}")
                            }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic
                    )
                    if (isStreaming) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    shape = CircleShape
                                )
                        )
                    }
                }
                if (!expanded && block.content.isNotBlank() && depth == 0) {
                    val preview = remember(block.content) {
                        val newlineIdx = block.content.indexOf('\n')
                        val firstLine = if (newlineIdx == -1) block.content else block.content.substring(0, newlineIdx)
                        if (firstLine.length > SUMMARY_PREVIEW_MAX_CHARS) firstLine.take(SUMMARY_PREVIEW_MAX_CHARS) + "…" else firstLine
                    }
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isStreaming) block.content + STREAMING_CURSOR else block.content,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontStyle = FontStyle.Italic,
                            lineHeight = 16.sp
                        )
                        block.children.forEach { child ->
                            Spacer(modifier = Modifier.height(4.dp))
                            ThinkingBlockView(
                                block = child,
                                isStreaming = isStreaming,
                                depth = depth + 1
                            )
                        }
                    }
                }
            }
        }
    }
}
