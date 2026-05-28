package com.lin.hippyagent.ui.settings.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.core.agent.config.MCPClientConfig
import com.lin.hippyagent.ui.components.HippyTopBar
import com.lin.hippyagent.ui.components.SettingCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPScreen(
    viewModel: MCPViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(
                message = "MCP 配置已保存",
                duration = SnackbarDuration.Short
            )
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = "MCP 客户端",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { viewModel.saveConfig() }) {
                        Icon(Icons.Default.Save, "保存", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showCreateDialog() },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "添加", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.mcpConfig.clients.entries.toList(), key = { it.key }) { (key, client) ->
                    MCPClientCard(
                        key = key,
                        client = client,
                        onToggle = { enabled -> viewModel.toggleClientEnabled(key, enabled) },
                        onEdit = { viewModel.showEditDialog(key) },
                        onDelete = { viewModel.deleteClient(key) }
                    )
                }

                if (uiState.mcpConfig.clients.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "暂无 MCP 客户端",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "点击右上角 + 添加",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showCreateDialog) {
        MCPCreateDialog(
            onDismiss = { viewModel.hideDialogs() },
            onConfirm = { key, config -> viewModel.addClient(key, config) }
        )
    }

    uiState.editingClientKey?.let { key ->
        val client = uiState.mcpConfig.clients[key]
        if (client != null) {
            MCPEditDialog(
                client = client,
                onDismiss = { viewModel.hideDialogs() },
                onConfirm = { updatedConfig -> viewModel.updateClient(key, updatedConfig) }
            )
        }
    }
}

@Composable
private fun MCPClientCard(
    key: String,
    client: MCPClientConfig,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (client.enabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = client.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "传输: ${client.transport}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "超时: ${client.readTimeoutSeconds.toLong()}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (client.description.isNotEmpty()) {
                        Text(
                            text = client.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Switch(
                    checked = client.enabled,
                    onCheckedChange = onToggle
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "编辑", modifier = Modifier.padding(end = 4.dp))
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        "删除",
                        modifier = Modifier.padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun MCPCreateDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, MCPClientConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var timeoutStr by remember { mutableStateOf("300") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 客户端") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("客户端标识 (key)") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("客户端名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("stdio", "streamable_http", "sse").forEach { t ->
                        TextButton(
                            onClick = { transport = t },
                            enabled = transport != t
                        ) {
                            Text(t)
                        }
                    }
                }
                if (transport == "stdio") {
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("命令 (command)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextField(
                    value = timeoutStr,
                    onValueChange = { timeoutStr = it },
                    label = { Text("执行超时（秒）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (key.isNotEmpty() && name.isNotEmpty()) {
                        onConfirm(
                            key,
                            MCPClientConfig(
                                name = name,
                                transport = transport,
                                command = command,
                                url = url,
                                readTimeoutSeconds = timeoutStr.toFloatOrNull() ?: 300f
                            )
                        )
                    }
                },
                enabled = key.isNotEmpty() && name.isNotEmpty()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MCPEditDialog(
    client: MCPClientConfig,
    onDismiss: () -> Unit,
    onConfirm: (MCPClientConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(client.name) }
    var description by remember { mutableStateOf(client.description) }
    var transport by remember { mutableStateOf(client.transport) }
    var command by remember { mutableStateOf(client.command) }
    var url by remember { mutableStateOf(client.url) }
    var cwd by remember { mutableStateOf(client.cwd) }
    var timeoutStr by remember { mutableStateOf(client.readTimeoutSeconds.toLong().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 MCP 客户端") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("客户端名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("stdio", "streamable_http", "sse").forEach { t ->
                        TextButton(
                            onClick = { transport = t },
                            enabled = transport != t
                        ) {
                            Text(t)
                        }
                    }
                }
                if (transport == "stdio") {
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("命令 (command)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("工作目录（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = timeoutStr,
                    onValueChange = { timeoutStr = it },
                    label = { Text("执行超时（秒）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotEmpty()) {
                        onConfirm(
                            client.copy(
                                name = name,
                                description = description,
                                transport = transport,
                                command = command,
                                url = url,
                                cwd = cwd,
                                readTimeoutSeconds = timeoutStr.toFloatOrNull() ?: 300f
                            )
                        )
                    }
                },
                enabled = name.isNotEmpty()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

