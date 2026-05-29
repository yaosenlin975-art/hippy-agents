package com.lin.hippyagent.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.plugin.PluginManager
import com.lin.hippyagent.core.plugin.PluginManifest
import com.lin.hippyagent.core.plugin.PluginToolDef
import com.lin.hippyagent.core.plugin.PluginParamDef
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PluginViewModel(
    private val pluginManager: PluginManager
) : ViewModel() {
    private val _plugins = MutableStateFlow<Map<String, PluginManifest>>(emptyMap())
    val plugins: StateFlow<Map<String, PluginManifest>> = _plugins

    init {
        refresh()
    }

    fun refresh() {
        _plugins.value = pluginManager.getLoadedPlugins()
    }

    fun savePlugin(manifest: PluginManifest) {
        pluginManager.savePlugin(manifest)
        refresh()
    }

    fun deletePlugin(name: String) {
        pluginManager.deletePlugin(name)
        refresh()
    }

    fun createSample(): PluginManifest = pluginManager.createSamplePlugin()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(
    viewModel: PluginViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.settings_plugins),
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.plugin_add))
            }
        }
    ) { padding ->
        if (plugins.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.plugin_no_plugins), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.plugin_add_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(plugins.entries.toList()) { (name, manifest) ->
                    PluginCard(
                        manifest = manifest,
                        onDelete = { viewModel.deletePlugin(name) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        CreatePluginDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { manifest ->
                viewModel.savePlugin(manifest)
                showCreateDialog = false
            },
            sample = viewModel.createSample()
        )
    }
}

@Composable
private fun PluginCard(manifest: PluginManifest, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(manifest.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (manifest.description.isNotBlank()) {
                        Text(manifest.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                }
            }
            if (manifest.tools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.plugin_tool_label), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                manifest.tools.forEach { tool ->
                    Text("  • ${tool.name}: ${tool.description}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CreatePluginDialog(
    onDismiss: () -> Unit,
    onCreate: (PluginManifest) -> Unit,
    sample: PluginManifest
) {
    var name by remember { mutableStateOf(sample.name) }
    var description by remember { mutableStateOf(sample.description) }
    var toolName by remember { mutableStateOf(sample.tools.firstOrNull()?.name ?: "") }
    var toolDescription by remember { mutableStateOf(sample.tools.firstOrNull()?.description ?: "") }
    var script by remember { mutableStateOf(sample.tools.firstOrNull()?.script ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.plugin_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.plugin_desc)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = toolName, onValueChange = { toolName = it }, label = { Text(stringResource(R.string.plugin_tool_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = toolDescription, onValueChange = { toolDescription = it }, label = { Text(stringResource(R.string.plugin_tool_desc)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text(stringResource(R.string.plugin_script)) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    PluginManifest(
                        name = name,
                        description = description,
                        tools = listOf(
                            PluginToolDef(
                                name = toolName,
                                description = toolDescription,
                                script = script
                            )
                        )
                    )
                )
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

