package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.task.TaskEntity

/**
 * ChatScreen 顶部 inline 审批卡片 — 当前 session 等待用户确认
 *
 * 显示在输入框上方, 用户无需离开对话即可批准/拒绝。
 * 覆盖 task + tool_approval 两种 source。
 */
@Composable
fun InlineApprovalCard(
    task: TaskEntity,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toolName = task.steps.firstOrNull()?.toolRef ?: task.title
    val prompt = task.approvalNodes.firstOrNull()?.prompt.orEmpty()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_inline_approval_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_inline_approval_subtitle, toolName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (prompt.isNotBlank() && prompt != toolName) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.chat_inline_approval_deny))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApprove) {
                    Text(stringResource(R.string.chat_inline_approval_approve))
                }
            }
        }
    }
}

/**
 * 其他 session / 无 session 等待审批 — 弹 Dialog
 *
 * 区别于 PermissionRequestDialog: 这条是别的会话在等, 只允许本次决策
 * (不允许 ALWAYS — 其他 session 的"总是"规则会跟 tool 级 ALWAYS 重复)
 */
@Composable
fun OtherSessionApprovalDialog(
    task: TaskEntity,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit
) {
    val toolName = task.steps.firstOrNull()?.toolRef ?: task.title
    val prompt = task.approvalNodes.firstOrNull()?.prompt.orEmpty()
    val sessionHint = task.sessionId ?: "后台任务"

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.chat_inline_approval_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.chat_other_session_approval_hint, sessionHint, toolName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (prompt.isNotBlank() && prompt != toolName) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(12.dp).fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.chat_inline_approval_deny))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onApprove) {
                        Text(stringResource(R.string.chat_inline_approval_approve))
                    }
                }
            }
        }
    }
}
