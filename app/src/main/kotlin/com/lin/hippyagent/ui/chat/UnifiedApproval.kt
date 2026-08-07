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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lin.hippyagent.R
import com.lin.hippyagent.core.security.RiskLevel
import com.lin.hippyagent.core.security.RiskTranslator

/**
 * 统一审批组件（设计文档「方案 A：风险分级」）。
 *
 * 合并 PermissionRequestDialog / InlineApprovalCard / OtherSessionApprovalDialog，
 * 按 [ApprovalUiData.riskLevel] 动态选择展示形态：
 * - LOW    → InlineApprovalCard（2 按钮，简短说明）
 * - MEDIUM → ApprovalBottomSheet（3 按钮 + 风险图标 + 自然语言翻译）
 * - HIGH+  → ApprovalDialog（5 按钮 + 命令详情 + 风险可视化色块）
 *
 * 跨会话审批（otherSessionApproval）复用同一个组件，通过 [ApprovalUiData.sessionHint]
 * 与 showAlwaysOptions=false 关闭「始终允许/不再允许」持久规则。
 */
data class ApprovalUiData(
    val title: String,
    val description: String,
    val command: String? = null,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val sessionHint: String? = null,
    val showAlwaysOptions: Boolean = true,
    val translation: String? = null
) {
    val naturalTranslation: String
        get() = translation ?: command?.let { RiskTranslator.translate(it) }.orEmpty()
}

@Composable
fun UnifiedApprovalComponent(
    request: ApprovalUiData,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onDenyOnce: () -> Unit,
    onDenyAlways: () -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when {
        request.riskLevel >= RiskLevel.HIGH -> HighRiskApprovalDialog(
            request = request,
            onApproveOnce = onApproveOnce,
            onApproveAlways = if (request.showAlwaysOptions) onApproveAlways else onApproveOnce,
            onDenyOnce = onDenyOnce,
            onDenyAlways = if (request.showAlwaysOptions) onDenyAlways else onDenyOnce,
            onDismiss = onDismiss
        )
        request.riskLevel == RiskLevel.MEDIUM -> MediumRiskApprovalSheet(
            request = request,
            onApproveOnce = onApproveOnce,
            onApproveAlways = if (request.showAlwaysOptions) onApproveAlways else onApproveOnce,
            onDenyOnce = onDenyOnce,
            onDenyAlways = if (request.showAlwaysOptions) onDenyAlways else onDenyOnce,
            onDismiss = onDismiss
        )
        else -> LowRiskApprovalCard(
            request = request,
            onApprove = onApproveOnce,
            onDeny = onDenyOnce,
            modifier = modifier
        )
    }
}

// ══════════════════════════ LOW：inline 卡片 ══════════════════════════

@Composable
private fun LowRiskApprovalCard(
    request: ApprovalUiData,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    text = request.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = request.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
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

// ══════════════════════════ MEDIUM：底部弹层 ══════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediumRiskApprovalSheet(
    request: ApprovalUiData,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onDenyOnce: () -> Unit,
    onDenyAlways: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.chat_risk_level_label, stringResource(R.string.risk_medium)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = request.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (request.sessionHint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = request.sessionHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = request.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            if (request.naturalTranslation.isNotBlank() && request.naturalTranslation != request.command) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = request.naturalTranslation,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDenyOnce,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.chat_deny))
                }
                if (request.showAlwaysOptions) {
                    OutlinedButton(
                        onClick = onApproveAlways,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_approve_always))
                    }
                }
                Button(
                    onClick = onApproveOnce,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.chat_approve_once))
                }
            }
            if (request.showAlwaysOptions) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDenyAlways) {
                        Text(
                            text = stringResource(R.string.chat_deny_always),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════ HIGH+：对话框 ══════════════════════════

@Composable
private fun HighRiskApprovalDialog(
    request: ApprovalUiData,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onDenyOnce: () -> Unit,
    onDenyAlways: () -> Unit,
    onDismiss: () -> Unit
) {
    val (blockColor, blockContent, riskIcon) = riskVisuals(request.riskLevel)

    Dialog(onDismissRequest = onDismiss) {
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
                // 风险可视化色块
                Surface(
                    color = blockColor,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = riskIcon,
                            contentDescription = null,
                            tint = blockContent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.chat_approval_risk_block),
                            style = MaterialTheme.typography.labelLarge,
                            color = blockContent
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.chat_risk_level_label, blockContentText(request.riskLevel)),
                            style = MaterialTheme.typography.labelMedium,
                            color = blockContent.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleMedium
                )
                if (request.sessionHint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = request.sessionHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 命令详情
                if (!request.command.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.chat_approval_command_detail),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = request.command,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 自然语言翻译
                if (request.naturalTranslation.isNotBlank() && request.naturalTranslation != request.command) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = request.naturalTranslation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 说明文字
                if (request.description.isNotBlank() && request.description != request.command) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = request.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(20.dp))
                // 5 按钮（showAlwaysOptions=false 时为 3 按钮）：拒绝 / 不再允许 / 始终允许 / 允许一次 / 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.chat_approval_close))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDenyOnce,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_deny))
                    }
                    if (request.showAlwaysOptions) {
                        OutlinedButton(
                            onClick = onDenyAlways,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.chat_deny_always))
                        }
                        FilledTonalButton(
                            onClick = onApproveAlways,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.chat_approve_always))
                        }
                    }
                    Button(
                        onClick = onApproveOnce,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_approve_once))
                    }
                }
            }
        }
    }
}

@Composable
private fun riskVisuals(riskLevel: RiskLevel): Triple<Color, Color, ImageVector> {
    return when {
        riskLevel >= RiskLevel.CRITICAL -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Error
        )
        riskLevel >= RiskLevel.HIGH -> Triple(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Error
        )
        else -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.Warning
        )
    }
}

@Composable
private fun blockContentText(riskLevel: RiskLevel): String {
    return when {
        riskLevel >= RiskLevel.CRITICAL -> stringResource(R.string.risk_critical)
        riskLevel >= RiskLevel.HIGH -> stringResource(R.string.risk_high)
        else -> stringResource(R.string.risk_medium)
    }
}
