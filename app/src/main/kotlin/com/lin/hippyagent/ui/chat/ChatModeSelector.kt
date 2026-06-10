package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.skill.AgentMode

/**
 * ChatScreen 顶部的 Auto|Chat|Work 三段式模式选择器。
 *
 * - 当前选中段以 primaryContainer 高亮
 * - modeLocked=true 时整个选择器隐藏(由调用方控制)
 */
@Composable
fun ChatModeSelector(
    currentMode: AgentMode,
    onSelect: (AgentMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoLabel = stringResource(R.string.chat_mode_auto)
    val chatLabel = stringResource(R.string.chat_mode_chat)
    val workLabel = stringResource(R.string.chat_mode_work)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ModeSegment(
                label = autoLabel,
                icon = Icons.Default.AutoAwesome,
                selected = currentMode == AgentMode.AUTO,
                onClick = { onSelect(AgentMode.AUTO) },
                modifier = Modifier.weight(1f)
            )
            ModeSegment(
                label = chatLabel,
                icon = Icons.AutoMirrored.Filled.Chat,
                selected = currentMode == AgentMode.CHAT,
                onClick = { onSelect(AgentMode.CHAT) },
                modifier = Modifier.weight(1f)
            )
            ModeSegment(
                label = workLabel,
                icon = Icons.Default.Work,
                selected = currentMode == AgentMode.WORK,
                onClick = { onSelect(AgentMode.WORK) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeSegment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = fg
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = fg
            )
        }
    }
}

/**
 * 顶栏紧凑型下拉 — 用于 TopAppBar.actions 中。
 * 替代原 ChatModeSelector 的横向三段显示，节省顶栏空间。
 * AUTO 模式时旁附 ⚙️ icon + 弹 tooltip 显示 autoDecidedModeReasoning。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatModeDropdown(
    selectedMode: AgentMode,
    onModeSelected: (AgentMode) -> Unit,
    autoDecidedModeReasoning: String? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val autoLabel = stringResource(R.string.chat_mode_auto)
    val chatLabel = stringResource(R.string.chat_mode_chat)
    val workLabel = stringResource(R.string.chat_mode_work)
    val noneLabel = stringResource(R.string.chat_mode_none)
    val (icon, label) = when (selectedMode) {
        AgentMode.AUTO -> Icons.Filled.AutoAwesome to autoLabel
        AgentMode.CHAT -> Icons.AutoMirrored.Filled.Chat to chatLabel
        AgentMode.WORK -> Icons.Filled.Work to workLabel
        AgentMode.NONE -> Icons.Filled.Block to noneLabel
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = modifier
                    .height(36.dp)
                    .combinedClickable(
                        onClick = { expanded = true },
                        onLongClick = { expanded = true }
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text = label, fontSize = 13.sp)
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AgentMode.entries.forEach { mode ->
                    val mLabel = when (mode) {
                        AgentMode.AUTO -> autoLabel
                        AgentMode.CHAT -> chatLabel
                        AgentMode.WORK -> workLabel
                        AgentMode.NONE -> noneLabel
                    }
                    DropdownMenuItem(
                        text = { Text(mLabel) },
                        onClick = {
                            onModeSelected(mode)
                            expanded = false
                        },
                        leadingIcon = {
                            val mIcon: ImageVector = when (mode) {
                                AgentMode.AUTO -> Icons.Filled.AutoAwesome
                                AgentMode.CHAT -> Icons.AutoMirrored.Filled.Chat
                                AgentMode.WORK -> Icons.Filled.Work
                                AgentMode.NONE -> Icons.Filled.Block
                            }
                            Icon(mIcon, contentDescription = null)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 紧凑型 Auto 决策提示 — 渲染在触发本次 auto 决策的用户 turn 下方。
 * 视觉上仅一行 chip：⚙️ 决策结果 + 切回手动 链接。
 */
@Composable
fun AutoDecisionHint(
    decidedMode: String,
    source: String,
    reasoning: String? = null,
    onSwitchToManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.chat_mode_auto_decision, decidedMode, source),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!reasoning.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_mode_auto_decision_reasoning, reasoning),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_mode_switch_to_manual),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onSwitchToManual() }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
