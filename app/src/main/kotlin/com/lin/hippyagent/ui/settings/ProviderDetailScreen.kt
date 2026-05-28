package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.model.ModelConfig
import com.lin.hippyagent.core.model.ModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    provider: ModelProvider,
    viewModel: ModelProviderViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showModelDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelConfig?>(null) }
    var showEditApiKeyDialog by remember { mutableStateOf(false) }
    var showProtocolDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.testResult) {
        uiState.testResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearTestResult()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Text(
                    text = provider.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingModel = null
                    showModelDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "添加模型", tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ProviderInfoCard(
                    provider = provider,
                    onTestConnection = { viewModel.testConnection(provider) },
                    onFetchModels = { viewModel.fetchModels(provider) },
                    onEditApiKey = { showEditApiKeyDialog = true },
                    onSwitchProtocol = { showProtocolDialog = true },
                    isTesting = uiState.isTestingConnection,
                    isFetching = uiState.isFetchingModels
                )
            }

            item {
                Text(
                    text = "模型列表 (${provider.models.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(provider.models) { model ->
                ModelCard(
                    model = model,
                    onEdit = {
                        editingModel = model
                        showModelDialog = true
                    },
                    onDelete = { viewModel.deleteModel(provider.id, model.id) },
                    onSetDefault = { viewModel.setDefaultModel(provider.id, model.id) }
                )
            }

            if (provider.models.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = "暂无模型，点击 + 添加或从服务器获取",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showModelDialog) {
        ModelConfigDialog(
            providerId = provider.id,
            model = editingModel,
            onDismiss = {
                showModelDialog = false
                editingModel = null
            },
            onConfirm = { model ->
                if (editingModel != null) {
                    viewModel.updateModel(provider.id, model)
                } else {
                    viewModel.addModel(provider.id, model)
                }
                showModelDialog = false
                editingModel = null
            }
        )
    }

    if (showEditApiKeyDialog) {
        EditApiKeyDialog(
            currentApiKey = provider.apiKey,
            onDismiss = { showEditApiKeyDialog = false },
            onConfirm = { newKey ->
                val cleanKey = newKey.filter { it.code <= 0x7F }.trim()
                viewModel.updateProvider(provider.copy(apiKey = cleanKey))
                showEditApiKeyDialog = false
            }
        )
    }

    if (showProtocolDialog) {
        SwitchProtocolDialog(
            currentProtocol = provider.protocol,
            onDismiss = { showProtocolDialog = false },
            onConfirm = { newProtocol ->
                viewModel.updateProvider(provider.copy(protocol = newProtocol))
                showProtocolDialog = false
            }
        )
    }
}

@Composable
private fun ProviderInfoCard(
    provider: ModelProvider,
    onTestConnection: () -> Unit,
    onFetchModels: () -> Unit,
    onEditApiKey: () -> Unit,
    onSwitchProtocol: () -> Unit,
    isTesting: Boolean,
    isFetching: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Base URL: ${provider.baseUrl}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "协议: ${when (provider.protocol) {
                    "anthropic" -> "Anthropic"
                    "ollama" -> "Ollama"
                    else -> "OpenAI"
                }}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "API Key: ${if (provider.apiKey.isNotEmpty()) "已配置" else "未配置"}",
                fontSize = 12.sp,
                color = if (provider.apiKey.isNotEmpty()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onTestConnection,
                    enabled = !isTesting
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text("测试连接")
                }
                TextButton(
                    onClick = onFetchModels,
                    enabled = !isFetching
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text("获取模型")
                }
                TextButton(onClick = onEditApiKey) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("修改 API Key")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onSwitchProtocol) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("切换协议类型")
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(
            containerColor = if (model.isDefault)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName.ifEmpty { model.name },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    if (model.isDefault) {
                        Text(
                            text = " (默认)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    model.temperature?.let {
                        Text(
                            text = "T: $it",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    model.maxTokens?.let {
                        Text(
                            text = "Max: $it",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    model.contextWindow?.let {
                        Text(
                            text = "Ctx: $it",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row {
                IconButton(onClick = onSetDefault) {
                    Icon(
                        imageVector = if (model.isDefault) Icons.Default.Star
                        else Icons.Default.StarBorder,
                        contentDescription = "设为默认",
                        tint = if (model.isDefault) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EditApiKeyDialog(
    currentApiKey: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 API Key") },
        text = {
            Column {
                Text(
                    text = "请输入新的 API Key",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(apiKey) },
                enabled = apiKey.isNotBlank()
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

@Composable
private fun SwitchProtocolDialog(
    currentProtocol: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedProtocol by remember { mutableStateOf(currentProtocol) }
    val protocols = listOf("openai" to "OpenAI 兼容", "anthropic" to "Anthropic", "ollama" to "Ollama")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换协议类型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "当前协议: ${protocols.firstOrNull { it.first == currentProtocol }?.second ?: currentProtocol}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                protocols.forEach { (value, label) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedProtocol == value)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedProtocol = value }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (selectedProtocol == value) Icons.Default.Check
                                else Icons.Default.Add,
                                contentDescription = null,
                                tint = if (selectedProtocol == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = if (selectedProtocol == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedProtocol) },
                enabled = selectedProtocol != currentProtocol
            ) {
                Text("切换")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

