package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.model.ModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProviderScreen(
    providers: List<ModelProvider>,
    onAddProvider: (ModelProvider) -> Unit,
    onUpdateProvider: (ModelProvider) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onBackClick: () -> Unit,
    onProviderClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            com.lin.hippyagent.ui.components.HippyTopBar(
                title = stringResource(R.string.model_provider),
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.add), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    onToggle = { enabled ->
                        onUpdateProvider(provider.copy(enabled = enabled))
                    },
                    onDelete = { onDeleteProvider(provider.id) },
                    onClick = { onProviderClick(provider.id) }
                )
            }

            if (providers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.provider_no_providers),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { provider ->
                onAddProvider(provider)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ModelProvider,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (provider.enabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
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
                        text = provider.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (provider.isDefault) {
                        Text(
                            text = stringResource(R.string.provider_default_suffix),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Text(
                    text = provider.baseUrl.ifEmpty { stringResource(R.string.provider_no_url) },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (provider.protocol) {
                            "anthropic" -> "Anthropic"
                            "ollama" -> "Ollama"
                            else -> "OpenAI"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = if (provider.apiKey.isNotEmpty()) "API Key: ✓" else "API Key: ✗",
                        fontSize = 12.sp,
                        color = if (provider.apiKey.isNotEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = stringResource(R.string.provider_model_count, provider.models.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (ModelProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("openai") }
    var showKey by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val protocols = listOf("openai" to stringResource(R.string.provider_protocol_openai_compat), "anthropic" to "Anthropic", "ollama" to "Ollama")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(stringResource(R.string.provider_id_label)) },
                    placeholder = { Text(stringResource(R.string.provider_id_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.provider_display_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedTextField(
                        value = protocols.firstOrNull { it.first == protocol }?.second ?: protocol,
                        onValueChange = {},
                        label = { Text(stringResource(R.string.provider_protocol_type)) },
                        readOnly = true,
                        enabled = false,
                        trailingIcon = {
                            Icon(
                                imageVector = if (expanded) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        protocols.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    protocol = value
                                    if (baseUrl.isEmpty()) {
                                        baseUrl = when (value) {
                                            "anthropic" -> "https://api.anthropic.com"
                                            "ollama" -> "http://localhost:11434"
                                            else -> ""
                                        }
                                    }
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text(stringResource(R.string.placeholder_base_url)) },
                    modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ModelProvider(
                            id = id,
                            name = name.ifEmpty { id },
                            baseUrl = baseUrl,
                            apiKey = apiKey.filter { it.code <= 0x7F }.trim(),
                            protocol = protocol
                        )
                    )
                },
                enabled = id.isNotBlank()
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
