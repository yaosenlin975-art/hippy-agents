package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.security.ApprovalAction
import com.lin.hippyagent.core.security.ApprovalRule
import com.lin.hippyagent.core.security.ToolApprovalManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class SecurityRulesViewModel(
    private val approvalManager: ToolApprovalManager
) : ViewModel() {
    private val _rules = MutableStateFlow<List<ApprovalRule>>(emptyList())
    val rules: StateFlow<List<ApprovalRule>> = _rules.asStateFlow()

    init {
        viewModelScope.launch { refreshRules() }
    }

    private suspend fun refreshRules() {
        _rules.value = approvalManager.getAllRules()
    }

    fun removeRule(key: String) {
        viewModelScope.launch {
            approvalManager.removeRule(key)
            refreshRules()
        }
    }

    fun clearAllRules() {
        viewModelScope.launch {
            approvalManager.clearAllRules()
            refreshRules()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityRulesScreen(
    onBack: () -> Unit,
    viewModel: SecurityRulesViewModel = koinViewModel()
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (rules.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "清除全部")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无规则", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当你在审批工具时选择「始终允许」或「不再允许」，规则会出现在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "以下规则会在工具审批时自动匹配，点击删除图标可撤销规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(rules, key = { it.key }) { rule ->
                    RuleCard(
                        rule = rule,
                        onDelete = { viewModel.removeRule(rule.key) }
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除全部规则") },
            text = { Text("确定要清除所有安全规则吗？清除后所有工具审批将重新询问。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllRules()
                    showClearDialog = false
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RuleCard(rule: ApprovalRule, onDelete: () -> Unit) {
    val parts = rule.key.split("|")
    val toolName = parts.firstOrNull() ?: rule.key
    val argHint = parts.drop(1).firstOrNull()?.take(40) ?: ""
    val actionLabel = when (rule.action) {
        ApprovalAction.ALLOW_ALWAYS -> "始终允许"
        ApprovalAction.DENY_ALWAYS -> "不再允许"
        else -> rule.action.name
    }
    val actionColor = when (rule.action) {
        ApprovalAction.ALLOW_ALWAYS -> MaterialTheme.colorScheme.primary
        ApprovalAction.DENY_ALWAYS -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.isDenied) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(toolName, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                if (argHint.isNotEmpty()) {
                    Text(
                        argHint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(actionLabel, fontSize = 12.sp, color = actionColor)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除规则",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
