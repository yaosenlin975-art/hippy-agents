package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.lin.hippyagent.core.storage.SecureStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyManagementScreen(
    secureStorage: SecureStorage,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeys by remember { mutableStateOf(secureStorage.listApiKeys()) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            com.lin.hippyagent.ui.components.HippyTopBar(
                title = stringResource(R.string.api_key_management),
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
            items(apiKeys, key = { it }) { providerId ->
                ApiKeyCard(
                    providerId = providerId,
                    hasKey = secureStorage.hasApiKey(providerId),
                    onDelete = {
                        secureStorage.removeApiKey(providerId)
                        apiKeys = secureStorage.listApiKeys()
                    }
                )
            }

            if (apiKeys.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.api_key_no_keys),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddApiKeyDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { providerId, apiKey ->
                val cleanKey = apiKey.filter { it.code <= 0x7F }.trim()
                secureStorage.saveApiKey(providerId, cleanKey)
                apiKeys = secureStorage.listApiKeys()
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ApiKeyCard(
    providerId: String,
    hasKey: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = providerId,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (hasKey) stringResource(R.string.api_key_configured) else stringResource(R.string.api_key_not_configured),
                    fontSize = 12.sp,
                    color = if (hasKey) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showKey) stringResource(R.string.api_key_hide) else stringResource(R.string.api_key_show)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddApiKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var providerId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.api_key_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = providerId,
                    onValueChange = { providerId = it },
                    label = { Text(stringResource(R.string.provider_id_label)) },
                    placeholder = { Text(stringResource(R.string.api_key_provider_id_hint)) },
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
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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
                onClick = { onConfirm(providerId, apiKey) },
                enabled = providerId.isNotBlank() && apiKey.isNotBlank()
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
