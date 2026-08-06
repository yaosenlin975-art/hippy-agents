package com.lin.hippyagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.security.RiskTranslator

/**
 * ChatScreen 顶部 inline 审批卡片 — 当前 session 等待用户确认。
 *
 * 统一审批组件（方案 A：风险分级）的 LOW 形态入口：
 * 按工具名估计风险，风险低 → inline 卡片（2 按钮）；中 → 底部弹层；高 → 对话框。
 * 覆盖 task + tool_approval 两种 source。
 */
@Composable
fun InlineApprovalCard(
    task: TaskEntity,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    onApproveAlways: () -> Unit = onApprove,
    onDenyAlways: () -> Unit = onDeny
) {
    val toolName = task.steps.firstOrNull()?.toolRef ?: task.title
    val prompt = task.approvalNodes.firstOrNull()?.prompt.orEmpty()
    val payload = task.steps.firstOrNull()?.payload
    val command = payload?.takeIf { it.isNotBlank() } ?: toolName

    UnifiedApprovalComponent(
        request = ApprovalUiData(
            title = stringResource(R.string.chat_inline_approval_title),
            description = stringResource(R.string.chat_inline_approval_subtitle, toolName)
                .let { if (prompt.isNotBlank() && prompt != toolName) "$it\n$prompt" else it },
            command = command,
            riskLevel = RiskTranslator.estimateToolRisk(toolName),
            showAlwaysOptions = true
        ),
        onApproveOnce = onApprove,
        onApproveAlways = onApproveAlways,
        onDenyOnce = onDeny,
        onDenyAlways = onDenyAlways,
        onDismiss = onDeny,
        modifier = modifier
    )
}

/**
 * 其他 session / 无 session 等待审批 — 复用统一审批组件。
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
    val sessionHint = task.sessionId ?: stringResource(R.string.chat_inline_approval_background_task)
    val payload = task.steps.firstOrNull()?.payload
    val command = payload?.takeIf { it.isNotBlank() } ?: toolName

    UnifiedApprovalComponent(
        request = ApprovalUiData(
            title = stringResource(R.string.chat_inline_approval_title),
            description = stringResource(R.string.chat_other_session_approval_hint, sessionHint, toolName)
                .let { if (prompt.isNotBlank() && prompt != toolName) "$it\n$prompt" else it },
            command = command,
            riskLevel = RiskTranslator.estimateToolRisk(toolName),
            sessionHint = stringResource(R.string.chat_other_session_approval_hint, sessionHint, toolName),
            showAlwaysOptions = false
        ),
        onApproveOnce = onApprove,
        onApproveAlways = onApprove,
        onDenyOnce = onDeny,
        onDenyAlways = onDeny,
        onDismiss = onDismiss
    )
}
