package com.lin.hippyagent.ui.group

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
import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.bootstrap.BootstrapHook
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun CreateGroupDialog(
    agentRepository: AgentRepository,
    onDismiss: () -> Unit,
    onCreateGroup: (name: String, agentIds: List<String>) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedAgentIds by remember { mutableStateOf(setOf<String>()) }
    var agents by remember { mutableStateOf<List<AgentProfile>>(emptyList()) }
    var bootstrapAgentIds by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val profiles = agentRepository.loadAgentProfiles().first()
        agents = profiles.values.toList()
        bootstrapAgentIds = agents
            .filter { BootstrapHook(agentRepository.getAgentWorkspaceDir(it.agentId)).isBootstrapMode() }
            .map { it.agentId }
            .toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建群组", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("群组名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("选择成员", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                if (agents.isEmpty()) {
                    Text("暂无可用智能体", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(agents, key = { it.agentId }) { agent ->
                            val isBootstrap = agent.agentId in bootstrapAgentIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = agent.agentId in selectedAgentIds,
                                    onCheckedChange = { checked ->
                                        if (isBootstrap) return@Checkbox
                                        selectedAgentIds = if (checked) {
                                            selectedAgentIds + agent.agentId
                                        } else {
                                            selectedAgentIds - agent.agentId
                                        }
                                    },
                                    enabled = !isBootstrap
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = agent.name.ifEmpty { agent.agentId },
                                        fontSize = 14.sp,
                                        color = if (isBootstrap) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isBootstrap) {
                                        Text(
                                            text = "未初始化，请先与该智能体对话完成初始化",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                        )
                                    } else {
                                        Text(
                                            text = agent.modelName,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreateGroup(groupName, selectedAgentIds.toList()) },
                enabled = groupName.isNotBlank() && selectedAgentIds.size >= 2
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
