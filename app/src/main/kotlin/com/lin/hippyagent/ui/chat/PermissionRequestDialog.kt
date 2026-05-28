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
import androidx.compose.ui.window.Dialog

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
                        text = if (isCustomPerm) "工具权限授权" else "命令授权请求",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isCustomPerm) {
                    val permKeys = command.removePrefix("CUSTOM_TOOL_PERM:").split(",")
                    val permLabels = permKeys.map { perm ->
                        when (perm.trim()) {
                            "DEVICE_ACCESS" -> "设备访问（电池、传感器、位置等）"
                            "CLIPBOARD_ACCESS" -> "剪贴板访问（读取/写入剪贴板）"
                            "SSH_SERVER" -> "SSH 服务管理（启动/停止/用户管理）"
                            "FILE_TRANSFER" -> "文件传输（Android 与容器间传输文件）"
                            "SHELL_EXECUTE" -> "Shell 命令执行"
                            else -> perm.trim()
                        }
                    }

                    Text(
                        text = "智能体请求使用以下功能，请确认是否允许：",
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
                        text = "Agent 请求执行以下命令：",
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
                        Text("拒绝")
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedButton(
                        onClick = onDenyAlways,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("不再允许")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalButton(onClick = onApproveAlways) {
                        Text("始终允许")
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(onClick = onApproveOnce) {
                        Text("允许一次")
                    }
                }
            }
        }
    }
}

