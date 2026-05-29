package com.lin.hippyagent.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.lin.hippyagent.R

/**
 * Shell 命令授权对话框
 *
 * 当 Agent 试图执行需要确认的命令时弹出
 * 选项：允许一次 / 始终允许 / 拒绝
 */
@Composable
fun PermissionRequestDialog(
    command: String,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onDenyOnce: () -> Unit,
    onDenyAlways: () -> Unit
) {
    val isCustomPerm = command.startsWith("CUSTOM_TOOL_PERM:")

    Dialog(onDismissRequest = onDenyOnce) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isCustomPerm) stringResource(R.string.chat_tool_permission_title) else stringResource(R.string.chat_command_auth_request),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isCustomPerm) {
                    val permKeys = command.removePrefix("CUSTOM_TOOL_PERM:").split(",")
                    val permLabels = permKeys.map { perm ->
                        when (perm.trim()) {
                            "DEVICE_ACCESS" -> stringResource(R.string.chat_perm_device_access)
                            "CLIPBOARD_ACCESS" -> stringResource(R.string.chat_perm_clipboard)
                            "SSH_SERVER" -> stringResource(R.string.chat_perm_ssh)
                            "FILE_TRANSFER" -> stringResource(R.string.chat_perm_file_transfer)
                            "SHELL_EXECUTE" -> stringResource(R.string.chat_perm_shell)
                            else -> perm.trim()
                        }
                    }

                    Text(
                        text = stringResource(R.string.chat_agent_request_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            permLabels.forEach { label ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "• $label",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.chat_agent_request_command),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = command,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDenyOnce,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.chat_deny))
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedButton(
                        onClick = onDenyAlways,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.chat_deny_always))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalButton(onClick = onApproveAlways) {
                        Text(stringResource(R.string.chat_approve_always))
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(onClick = onApproveOnce) {
                        Text(stringResource(R.string.chat_approve_once))
                    }
                }
            }
        }
    }
}

