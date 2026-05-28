package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolApprovalsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var requireApproval by remember { mutableStateOf(true) }
    var approvalTimeout by remember { mutableStateOf("30") }
    val pendingApprovals = remember { mutableStateListOf<String>() }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = "工具审批",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 说明
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("工具审批", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "配置工具调用的审批策略，确保敏感操作需要用户确认。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 需要审批开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("需要审批", fontSize = 14.sp)
                        Text("敏感工具调用前需要用户确认", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = requireApproval, onCheckedChange = { requireApproval = it })
                }
            }

            // 审批超时
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("审批超时（秒）", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = approvalTimeout,
                        onValueChange = { approvalTimeout = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = requireApproval
                    )
                }
            }

            // 待审批列表
            if (pendingApprovals.isNotEmpty()) {
                Text("待审批", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingApprovals.toList(), key = { it }) { toolName ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(toolName, fontSize = 14.sp)
                                    Text("等待审批", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { pendingApprovals.remove(toolName) }) {
                                    Text("批准")
                                }
                                TextButton(onClick = { pendingApprovals.remove(toolName) }) {
                                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

