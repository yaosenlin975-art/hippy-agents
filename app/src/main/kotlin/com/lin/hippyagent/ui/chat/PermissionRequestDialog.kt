package com.lin.hippyagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.security.RiskTranslator

/**
 * Shell 命令 / 自定义工具权限授权对话框（统一审批组件入口）。
 *
 * 当 Agent 试图执行需要确认的命令时弹出，按命令风险动态选择形态：
 * - LOW → inline 卡片（2 按钮）
 * - MEDIUM → 底部弹层（3 按钮 + 风险图标 + 自然语言翻译）
 * - HIGH+ → 对话框（5 按钮 + 命令详情 + 风险可视化色块）
 *
 * 选项：允许一次 / 始终允许 / 拒绝 / 不再允许（四选项逻辑不回退）
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

    val description = if (isCustomPerm) {
        val permLabels = command.removePrefix("CUSTOM_TOOL_PERM:").split(",").map { perm ->
            when (perm.trim()) {
                "DEVICE_ACCESS" -> stringResource(R.string.chat_perm_device_access)
                "CLIPBOARD_ACCESS" -> stringResource(R.string.chat_perm_clipboard)
                "SSH_SERVER" -> stringResource(R.string.chat_perm_ssh)
                "FILE_TRANSFER" -> stringResource(R.string.chat_perm_file_transfer)
                "SHELL_EXECUTE" -> stringResource(R.string.chat_perm_shell)
                else -> perm.trim()
            }
        }.joinToString("\n• ") { it }
        stringResource(R.string.chat_agent_request_permission) + (permLabels.takeIf { it.isNotBlank() }?.let { "\n• $it" } ?: "")
    } else {
        stringResource(R.string.chat_agent_request_command)
    }

    UnifiedApprovalComponent(
        request = ApprovalUiData(
            title = if (isCustomPerm) stringResource(R.string.chat_tool_permission_title) else stringResource(R.string.chat_command_auth_request),
            description = description,
            command = command,
            riskLevel = RiskTranslator.estimateRisk(command),
            showAlwaysOptions = true,
            translation = if (isCustomPerm) "" else null
        ),
        onApproveOnce = onApproveOnce,
        onApproveAlways = onApproveAlways,
        onDenyOnce = onDenyOnce,
        onDenyAlways = onDenyAlways,
        onDismiss = onDenyOnce
    )
}
