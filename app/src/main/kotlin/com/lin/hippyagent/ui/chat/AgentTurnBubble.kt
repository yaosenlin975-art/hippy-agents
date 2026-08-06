package com.lin.hippyagent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.core.chat.TurnMetadata
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R

private const val STREAMING_CURSOR = "▎"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ErrorBubble(
    content: String,
    isStreaming: Boolean,
    metadata: TurnMetadata?,
    onImageClick: ((String) -> Unit)?,
    agentProfiles: Map<String, String>,
    onLongClick: () -> Unit,
    onClick: () -> Unit = {},
    metadataJson: String? = null,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        )
    ) {
        AgentBubbleContent(
            responseContent = content,
            isStreaming = isStreaming,
            isError = true,
            metadata = metadata,
            onImageClick = onImageClick,
            agentProfiles = agentProfiles,
            showStreamingCursor = false,
            metadataJson = metadataJson,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContentBubble(
    content: String,
    isStreaming: Boolean,
    isGroupChat: Boolean,
    showStreamingCursor: Boolean,
    metadata: TurnMetadata?,
    onImageClick: ((String) -> Unit)?,
    agentProfiles: Map<String, String>,
    barKey: String? = null,
    onLongClick: () -> Unit,
    onClick: () -> Unit = {},
    metadataJson: String? = null,
    modifier: Modifier = Modifier
) {
    val agentBarColor = if (isGroupChat && barKey != null && barKey.isNotBlank()) {
        val hue = (barKey.hashCode().and(0x7FFFFFFF) % 360).toFloat()
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.75f)))
    } else null

    if (agentBarColor != null) {
        Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(agentBarColor, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
            ) {
                AgentBubbleContent(
                    responseContent = content,
                    isStreaming = isStreaming,
                    isError = false,
                    metadata = metadata,
                    onImageClick = onImageClick,
                    agentProfiles = agentProfiles,
                    showStreamingCursor = showStreamingCursor,
                    metadataJson = metadataJson
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            AgentBubbleContent(
                responseContent = content,
                isStreaming = isStreaming,
                isError = false,
                metadata = metadata,
                onImageClick = onImageClick,
                agentProfiles = agentProfiles,
                showStreamingCursor = showStreamingCursor,
                metadataJson = metadataJson
            )
        }
    }
}

@Composable
internal fun AgentBubbleContent(
    responseContent: String,
    isStreaming: Boolean,
    isError: Boolean,
    metadata: TurnMetadata?,
    onImageClick: ((String) -> Unit)? = null,
    agentProfiles: Map<String, String> = emptyMap(),
    showStreamingCursor: Boolean = false,
    metadataJson: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        val displayContent = if (showStreamingCursor && responseContent.isNotBlank()) {
            responseContent + STREAMING_CURSOR
        } else {
            responseContent
        }
        if (displayContent.isNotBlank()) {
            MessageContentWithAttachments(
                content = displayContent,
                isUser = false,
                onImageClick = onImageClick,
                agentProfiles = agentProfiles,
                metadataJson = metadataJson
            )
        }

        if (isError) {
            Text(
                text = stringResource(R.string.chat_error_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (metadata != null) {
            AnimatedVisibility(
                visible = !isStreaming,
                enter = fadeIn() + expandVertically()
            ) {
                TurnMetadataBar(metadata = metadata)
            }
        }
    }
}

@Composable
internal fun TurnMetadataBar(
    metadata: TurnMetadata,
    modifier: Modifier = Modifier
) {
    val items = mutableListOf<String>()
    if (metadata.totalTokens > 0) {
        if (metadata.inputTokens > 0 || metadata.outputTokens > 0) {
            items.add("↑${formatTokenCount(metadata.inputTokens)} ↓${formatTokenCount(metadata.outputTokens)}")
        } else {
            items.add("${formatTokenCount(metadata.totalTokens)} tok")
        }
    }
    if (metadata.cacheReadTokens > 0 || metadata.cacheWriteTokens > 0) {
        val cacheParts = mutableListOf<String>()
        if (metadata.cacheReadTokens > 0) cacheParts.add("CR${formatTokenCount(metadata.cacheReadTokens)}")
        if (metadata.cacheWriteTokens > 0) cacheParts.add("CW${formatTokenCount(metadata.cacheWriteTokens)}")
        items.add(cacheParts.joinToString(" "))
    }
    if (metadata.apiCalls > 0) {
        items.add(stringResource(R.string.chat_api_calls, metadata.apiCalls))
    }
    if (metadata.model.isNotBlank()) {
        items.add(if (metadata.isFallback) "🔄 ${metadata.model} (fallback)" else metadata.model)
    }
    if (metadata.latencyMs > 0) {
        items.add(if (metadata.latencyMs < 1000) "${metadata.latencyMs}ms" else "%.1fs".format(metadata.latencyMs / 1000.0))
    }
    if (items.isEmpty() && metadata.contextTokens <= 0) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        if (items.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = items.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        if (metadata.contextTokens > 0 && metadata.maxContextTokens > 0) {
            val ratio = (metadata.contextTokens.toFloat() / metadata.maxContextTokens.toFloat()).coerceIn(0f, 1f)
            val pct = (ratio * 100).toInt()
            val barColor = if (ratio > 0.5f) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatTokenCount(metadata.contextTokens)}/${formatTokenCount(metadata.maxContextTokens)} $pct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(3.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(1.5.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(48.dp * ratio)
                            .background(barColor, RoundedCornerShape(1.5.dp))
                    )
                }
            }
        }
    }
}

internal fun formatTokenCount(count: Long): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}
