package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.TurnElement

@Composable
internal fun ProcessDrawer(
    stepCount: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    stats: TurnProcessStats? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(0.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                    )
            )
            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isExpanded) "▼" else "▶",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (stats != null) {
                            stringResource(
                                R.string.chat_step_process_stats,
                                stepCount,
                                stats.thinkingCount,
                                stats.toolCount,
                                stats.messageCount,
                                stats.durationSec
                            )
                        } else {
                            stringResource(R.string.chat_step_process, stepCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }
    }
}

@Immutable
internal data class TurnProcessStats(
    val thinkingCount: Int,
    val toolCount: Int,
    val messageCount: Int,
    val durationSec: Int
)

internal fun computeTurnProcessStats(turn: ChatTurn.AgentTurn): TurnProcessStats {
    val thinkingCount = turn.elements.count { it is TurnElement.ThinkingSegment }
    val toolCount = turn.elements.count { it is TurnElement.ToolCallSegment }
    val textSegments = turn.elements.filterIsInstance<TurnElement.TextSegment>()
    val messageCount = (textSegments.size - 1).coerceAtLeast(0)
    val durationSec = ((turn.metadata?.latencyMs ?: 0L) / 1000L).toInt()
    return TurnProcessStats(thinkingCount, toolCount, messageCount, durationSec)
}

internal fun aggregateTurnProcessStats(turns: List<ChatTurn.AgentTurn>): TurnProcessStats {
    if (turns.isEmpty()) return TurnProcessStats(0, 0, 0, 0)
    var thinking = 0
    var tool = 0
    var message = 0
    var durationMs = 0L
    for (t in turns) {
        val s = computeTurnProcessStats(t)
        thinking += s.thinkingCount
        tool += s.toolCount
        message += s.messageCount
        durationMs += (t.metadata?.latencyMs ?: 0L)
    }
    return TurnProcessStats(thinking, tool, message, (durationMs / 1000L).toInt())
}

@Composable
internal fun ProcessCollapseButton(
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "▲", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.chat_collapse_process),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        }
    }
}
