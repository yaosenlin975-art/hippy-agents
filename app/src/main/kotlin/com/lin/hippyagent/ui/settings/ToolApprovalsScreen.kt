package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
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
                title = stringResource(R.string.tool_approval_title),
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.tool_approval_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tool_approval_desc),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.tool_approval_required), fontSize = 14.sp)
                        Text(stringResource(R.string.tool_approval_required_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = requireApproval, onCheckedChange = { requireApproval = it })
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.tool_approval_timeout_seconds_label), fontSize = 14.sp)
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

            if (pendingApprovals.isNotEmpty()) {
                Text(stringResource(R.string.tool_approval_pending_label), fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                                    Text(stringResource(R.string.tool_approval_waiting), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { pendingApprovals.remove(toolName) }) {
                                    Text(stringResource(R.string.tool_approval_approve))
                                }
                                TextButton(onClick = { pendingApprovals.remove(toolName) }) {
                                    Text(stringResource(R.string.tool_approval_deny), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
