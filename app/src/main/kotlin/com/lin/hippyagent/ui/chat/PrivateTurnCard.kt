package com.lin.hippyagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.chat.ChatTurn

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PrivateTurnCard(
    turn: ChatTurn.PrivateTurn,
    agentProfiles: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val targetName = turn.senderId?.let { agentProfiles[it] } ?: stringResource(R.string.chat_agent_fallback_name)
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val collapseTrigger = LocalCollapseAll.current
    val expandTrigger = LocalExpandVisible.current

    LaunchedEffect(collapseTrigger) {
        if (collapseTrigger > 0) {
            expanded = false
        }
    }
    LaunchedEffect(expandTrigger) {
        if (expandTrigger > 0) {
            expanded = true
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("discussion", turn.content))
                        Toast.makeText(context, context.getString(R.string.chat_discussion_copied), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = if (expanded) "▼" else "▶", fontSize = 10.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = context.getString(R.string.chat_discussion_with, targetName),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Text(
                    text = turn.content,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
