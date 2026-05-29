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
import com.lin.hippyagent.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPScreen(
    viewModel: MCPViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.mcp_config_saved),
                duration = SnackbarDuration.Short
            )
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.mcp_client_title),
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { viewModel.saveConfig() }) {
                        Icon(Icons.Default.Save, stringResource(R.string.save), tint = MaterialTheme.colorScheme.onPrimary)
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
                Icon(Icons.Default.Add, stringResource(R.string.add), tint = MaterialTheme.colorScheme.onPrimary)
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
                                    text = stringResource(R.string.mcp_no_clients),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.mcp_add_hint),
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
                        text = stringResource(R.string.mcp_transport_label, client.transport),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.mcp_timeout_label, client.readTimeoutSeconds.toLong()),
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
                    Icon(Icons.Default.Edit, stringResource(R.string.edit), modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.edit))
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.delete),
                        modifier = Modifier.padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
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
        title = { Text(stringResource(R.string.mcp_add_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.mcp_client_key)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_client_name)) },
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
                        label = { Text(stringResource(R.string.mcp_command_label)) },
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
                    label = { Text(stringResource(R.string.mcp_timeout_seconds)) },
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
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
        title = { Text(stringResource(R.string.mcp_edit_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_client_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.mcp_description_optional)) },
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
                        label = { Text(stringResource(R.string.mcp_command_label)) },
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
                    label = { Text(stringResource(R.string.mcp_cwd_optional)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = timeoutStr,
                    onValueChange = { timeoutStr = it },
                    label = { Text(stringResource(R.string.mcp_timeout_seconds)) },
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
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

