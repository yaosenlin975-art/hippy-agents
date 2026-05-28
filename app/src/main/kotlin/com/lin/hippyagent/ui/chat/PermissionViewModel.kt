package com.lin.hippyagent.ui.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.lin.hippyagent.core.accessibility.ActionApprover
import com.lin.hippyagent.core.accessibility.ApprovalRequest
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.PermissionType
import com.lin.hippyagent.core.security.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class PermissionUiState(
    val pendingPermissionCommand: String? = null,
    val missingPermissions: List<String> = emptyList()
)

class PermissionViewModel(
    private val actionApprover: ActionApprover,
    private val agentFactory: AgentFactory,
    private val permissionManager: PermissionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionUiState())
    val uiState: StateFlow<PermissionUiState> = _uiState.asStateFlow()

    val pendingApprovalRequest: StateFlow<ApprovalRequest?> = actionApprover.pendingRequest

    var onAddPermissionTurn: ((ChatTurn.PermissionTurn) -> Unit)? = null
    var onResolvePermissionTurns: (() -> Unit)? = null
    var deliveryScopeProvider: (() -> CoroutineScope)? = null

    fun handlePermissionNeeded(command: String) {
        val permType = if (command.startsWith("CUSTOM_TOOL_PERM:")) {
            PermissionType.CUSTOM_TOOL
        } else {
            PermissionType.SHELL_COMMAND
        }
        val title = if (permType == PermissionType.CUSTOM_TOOL) "工具权限请求" else "Shell 执行请求"
        val desc = if (permType == PermissionType.CUSTOM_TOOL) {
            command.removePrefix("CUSTOM_TOOL_PERM:")
        } else {
            "智能体请求执行以下命令"
        }
        val permTurn = ChatTurn.PermissionTurn(
            id = "perm_${System.currentTimeMillis()}",
            title = title,
            description = desc,
            permissionType = permType,
            pendingCommand = command,
            isResolved = false
        )
        _uiState.update { it.copy(pendingPermissionCommand = command) }
        onAddPermissionTurn?.invoke(permTurn)
    }

    fun approvePermissionOnce(sessionId: String, agentId: String) {
        val command = _uiState.value.pendingPermissionCommand ?: return
        if (command.startsWith("CUSTOM_TOOL_PERM:")) {
            val scope = deliveryScopeProvider?.invoke() ?: return
            scope.launch {
                val permKeys = command.removePrefix("CUSTOM_TOOL_PERM:").split(",").map { it.trim() }
                permissionManager.approveCustomToolPermission(permKeys, persistent = false)
            }
        } else {
            val scope = deliveryScopeProvider?.invoke() ?: return
            scope.launch {
                try {
                    permissionManager.addTempScope("/tmp", read = true, write = true)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to add temp scope")
                }
            }
        }
        resolvePermissionTurn(sessionId, agentId)
    }

    fun approvePermissionAlways(sessionId: String, agentId: String) {
        val command = _uiState.value.pendingPermissionCommand ?: return
        if (command.startsWith("CUSTOM_TOOL_PERM:")) {
            val scope = deliveryScopeProvider?.invoke() ?: return
            scope.launch {
                val permKeys = command.removePrefix("CUSTOM_TOOL_PERM:").split(",").map { it.trim() }
                permissionManager.approveCustomToolPermission(permKeys, persistent = true)
            }
        } else {
            val scope = deliveryScopeProvider?.invoke() ?: return
            scope.launch {
                try {
                    permissionManager.addTempScope("/tmp", read = true, write = true)
                    permissionManager.addPersistentScope("/workspace", read = true, write = true)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to add persistent scope")
                }
            }
        }
        resolvePermissionTurn(sessionId, agentId)
    }

    fun denyPermissionOnce(sessionId: String, agentId: String) {
        resolvePermissionTurn(sessionId, agentId)
    }

    fun denyPermissionAlways(sessionId: String, agentId: String) {
        val command = _uiState.value.pendingPermissionCommand ?: return
        if (command.startsWith("CUSTOM_TOOL_PERM:")) {
            val permKeys = command.removePrefix("CUSTOM_TOOL_PERM:").split(",").map { it.trim() }
            val scope = deliveryScopeProvider?.invoke() ?: return
            scope.launch {
                permissionManager.denyCustomToolPermanently(permKeys)
            }
        } else {
            val commandPrefix = command.split(" ").firstOrNull() ?: command
            val scope = deliveryScopeProvider?.invoke() ?: return
            scope.launch {
                permissionManager.denyCustomToolPermanently(listOf("shell_$commandPrefix"))
            }
        }
        resolvePermissionTurn(sessionId, agentId)
    }

    fun respondToApproval(approved: Boolean, duration: com.lin.hippyagent.core.accessibility.ApprovalDuration = com.lin.hippyagent.core.accessibility.ApprovalDuration.ONCE) {
        actionApprover.respond(com.lin.hippyagent.core.accessibility.ApprovalResult(approved, duration))
    }

    fun clearMissingPermissions() {
        _uiState.update { it.copy(missingPermissions = emptyList()) }
    }

    fun updatePermissionState(pendingCommand: String?, missingPerms: List<String>) {
        if (pendingCommand != null) {
            _uiState.update { it.copy(pendingPermissionCommand = pendingCommand) }
        }
        if (missingPerms.isNotEmpty()) {
            _uiState.update { it.copy(missingPermissions = missingPerms) }
        }
    }

    private fun resolvePermissionTurn(sessionId: String, agentId: String) {
        _uiState.update { it.copy(pendingPermissionCommand = null) }
        onResolvePermissionTurns?.invoke()
        val scope = deliveryScopeProvider?.invoke() ?: return
        scope.launch {
            val agent = agentFactory.getAgent(agentId)
            agent?.clearPendingPermission(sessionId)
        }
    }
}
