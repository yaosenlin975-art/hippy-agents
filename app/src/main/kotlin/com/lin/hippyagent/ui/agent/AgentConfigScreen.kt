package com.lin.hippyagent.ui.agent

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.chat.ModelSwitchSheet
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigScreen(
    viewModel: AgentConfigViewModel,
    onBackClick: () -> Unit,
    onNavigateToRunningConfig: (String) -> Unit = {},
    onNavigateToHeartbeat: (String) -> Unit = {},
    onNavigateToDream: (String) -> Unit = {},
    onNavigateToMCP: (String) -> Unit = {},
    onNavigateToACP: (String) -> Unit = {},
    onNavigateToCronJobs: (String) -> Unit = {},
    onNavigateToStore: () -> Unit = {},
    onNavigateToChannelConfig: (String) -> Unit = {},
    onNavigateToModelProvider: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModelSelector by remember { mutableStateOf(false) }
    var showFallbackModelSelector by remember { mutableStateOf(false) }
    var showComplexModelSelector by remember { mutableStateOf(false) }
    var editingFilename by remember { mutableStateOf<String?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var isEditingContentLoaded by remember { mutableStateOf(false) }
    var memorySectionExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var showSkillsSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.saveAvatarToInternalStorage(it) { }
        }
    }

    val skillManager = org.koin.compose.koinInject<com.lin.hippyagent.core.skill.SkillManager>()
    val scope = rememberCoroutineScope()
    var installResultMessage by remember { mutableStateOf<String?>(null) }
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val tempFile = java.io.File(context.cacheDir, "skill_install.zip")
                    context.contentResolver.openInputStream(it)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val result = skillManager.installSkillFromZip(tempFile.absolutePath)
                    result.fold(
                        onSuccess = { skill ->
                            installResultMessage = context.getString(R.string.agent_skill_installed, skill.name)
                        },
                        onFailure = { e ->
                            installResultMessage = context.getString(R.string.agent_skill_install_failed, e.message ?: "")
                        }
                    )
                    tempFile.delete()
                } catch (e: Exception) {
                    installResultMessage = context.getString(R.string.agent_skill_install_failed, e.message ?: "")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = uiState.agent?.name?.ifEmpty { uiState.agent?.agentId } ?: stringResource(R.string.agent_config),
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    if (uiState.agent?.isDefault != true) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.agent != null) {
            val agent = uiState.agent!!

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    Text(stringResource(R.string.agent_basic_info), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                }

                item {
                    AgentProfileFields(
                        avatarUrl = agent.avatarUrl,
                        onPickImage = { imagePickerLauncher.launch("image/*") },
                        onResetAvatar = { viewModel.updateAvatarUrl("") },
                        name = agent.name,
                        onNameChange = viewModel::updateName,
                        enabled = agent.enabled,
                        onEnabledChange = { viewModel.updateEnabled(it) },
                        modelName = agent.modelName,
                        modelProvider = agent.modelProvider,
                        fallbackModelName = agent.fallbackModelName,
                        fallbackModelProvider = agent.fallbackModelProvider,
                        complexModelName = agent.complexModelName,
                        complexModelProvider = agent.complexModelProvider,
                        providerNames = uiState.providerNames,
                        onShowModelSelector = { showModelSelector = true },
                        onShowFallbackModelSelector = { showFallbackModelSelector = true },
                        onShowComplexModelSelector = { showComplexModelSelector = true }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.agent_quick_config),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            QuickActionButton(
                                icon = Icons.Default.Settings,
                                label = stringResource(R.string.running_config),
                                onClick = { uiState.agent?.agentId?.let { onNavigateToRunningConfig(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.FavoriteBorder,
                                label = stringResource(R.string.agent_heartbeat_config),
                                onClick = { uiState.agent?.agentId?.let { onNavigateToHeartbeat(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.Nightlight,
                                label = stringResource(R.string.agent_dream_system),
                                onClick = { uiState.agent?.agentId?.let { onNavigateToDream(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.Power,
                                label = stringResource(R.string.agent_mcp_config),
                                onClick = { uiState.agent?.agentId?.let { onNavigateToMCP(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.Handshake,
                                label = stringResource(R.string.agent_acp_config),
                                onClick = { uiState.agent?.agentId?.let { onNavigateToACP(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.Extension,
                                label = stringResource(R.string.skill_management),
                                onClick = { showSkillsSheet = true },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.Build,
                                label = stringResource(R.string.agent_tool_management),
                                onClick = { showToolsSheet = true },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.Schedule,
                                label = stringResource(R.string.agent_cron_jobs),
                                onClick = { uiState.agent?.agentId?.let { onNavigateToCronJobs(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            QuickActionButton(
                                icon = Icons.Default.Email,
                                label = stringResource(R.string.agent_channel_config),
                                onClick = { uiState.agent?.agentId?.let { onNavigateToChannelConfig(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    Text(stringResource(R.string.core_files), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                }

                val allMdFiles = (uiState.workspaceMdFiles + agent.coreFiles).distinct().sorted()
                val regularFiles = allMdFiles.filter { !it.startsWith("memory/") }
                val memoryFiles = allMdFiles.filter { it.startsWith("memory/") }

                items(regularFiles, key = { it }) { filename ->
                    CoreFileItem(
                        filename = filename,
                        isEnabled = agent.coreFiles.contains(filename),
                        onToggle = { enabled ->
                            val currentCoreFiles = agent.coreFiles.toMutableList()
                            if (enabled && !currentCoreFiles.contains(filename)) {
                                currentCoreFiles.add(filename)
                            } else if (!enabled && currentCoreFiles.contains(filename)) {
                                currentCoreFiles.remove(filename)
                            }
                            viewModel.updateCoreFiles(currentCoreFiles)
                        },
                        onClick = {
                            editingFilename = filename
                            isEditingContentLoaded = false
                            viewModel.loadCoreFileContent(filename) { content ->
                                editingContent = content
                                isEditingContentLoaded = true
                            }
                        },
                        onDelete = { viewModel.deleteCoreFile(filename) {} }
                    )
                }

                if (memoryFiles.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { memorySectionExpanded = !memorySectionExpanded }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("memories", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        context.getString(R.string.agent_file_count, memoryFiles.size),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (memorySectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (memorySectionExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    item {
                        AnimatedVisibility(
                            visible = memorySectionExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
                                memoryFiles.forEach { filename ->
                                    CoreFileItem(
                                        filename = filename,
                                        isEnabled = agent.coreFiles.contains(filename),
                                        onToggle = { enabled ->
                                            val currentCoreFiles = agent.coreFiles.toMutableList()
                                            if (enabled && !currentCoreFiles.contains(filename)) {
                                                currentCoreFiles.add(filename)
                                            } else if (!enabled && currentCoreFiles.contains(filename)) {
                                                currentCoreFiles.remove(filename)
                                            }
                                            viewModel.updateCoreFiles(currentCoreFiles)
                                        },
                                        onClick = {
                                            editingFilename = filename
                                            isEditingContentLoaded = false
                                            viewModel.loadCoreFileContent(filename) { content ->
                                                editingContent = content
                                                isEditingContentLoaded = true
                                            }
                                        },
                                        onDelete = { viewModel.deleteCoreFile(filename) {} }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            if (showModelSelector) {
                ModelSwitchSheet(
                    selectedModel = agent.modelName,
                    selectedProviderId = agent.modelProvider,
                    availableModels = uiState.availableModels,
                    onModelSelected = { model, providerId ->
                        viewModel.updateModel(model, providerId)
                        showModelSelector = false
                    },
                    onDismiss = { showModelSelector = false },
                    onNavigateToSettings = {
                        showModelSelector = false
                        onNavigateToModelProvider()
                    }
                )
            }

            if (showFallbackModelSelector) {
                ModelSwitchSheet(
                    selectedModel = agent.fallbackModelName,
                    availableModels = uiState.availableModels,
                    onModelSelected = { model, providerId ->
                        viewModel.updateFallbackModel(model, providerId)
                        showFallbackModelSelector = false
                    },
                    onDismiss = { showFallbackModelSelector = false },
                    onNavigateToSettings = {
                        showFallbackModelSelector = false
                        onNavigateToModelProvider()
                    }
                )
            }

            if (showComplexModelSelector) {
                ModelSwitchSheet(
                    selectedModel = agent.complexModelName,
                    availableModels = uiState.availableModels,
                    onModelSelected = { model, providerId ->
                        viewModel.updateComplexModel(model, providerId)
                        showComplexModelSelector = false
                    },
                    onDismiss = { showComplexModelSelector = false },
                    onNavigateToSettings = {
                        showComplexModelSelector = false
                        onNavigateToModelProvider()
                    }
                )
            }

            if (editingFilename != null && isEditingContentLoaded) {
                val currentFilename = editingFilename!!
                val currentContent = editingContent
                androidx.compose.runtime.key(currentFilename) {
                    CoreFileEditorDialog(
                        filename = currentFilename,
                        content = currentContent,
                        onDismiss = { editingFilename = null },
                        onSave = { content ->
                            viewModel.saveCoreFile(currentFilename, content) {
                                editingFilename = null
                                Toast.makeText(context, context.getString(R.string.agent_saved), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            if (showSkillsSheet) {
                SkillsManagementSheet(
                    skills = uiState.agent?.skills ?: emptyList(),
                    disabledSkills = uiState.agent?.disabledSkills ?: emptyList(),
                    skillManager = skillManager,
                    onToggleSkillEnabled = { skillId, enabled -> viewModel.toggleSkillEnabled(skillId, enabled) },
                    onUpdateSkills = { viewModel.updateSkills(it) },
                    onNavigateToStore = onNavigateToStore,
                    onInstallFromZip = { zipPickerLauncher.launch("application/zip") },
                    onSyncResult = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
                    onDismiss = { showSkillsSheet = false }
                )
            }

            if (showToolsSheet) {
                ToolsManagementSheet(
                    disabledTools = uiState.agent?.disabledTools ?: emptyList(),
                    onToggleToolEnabled = { toolName, enabled -> viewModel.toggleToolEnabled(toolName, enabled) },
                    onDismiss = { showToolsSheet = false }
                )
            }
        }
    }

    if (showDeleteDialog) {
        DeleteAgentDialog(
            agentName = uiState.agent?.name?.ifEmpty { uiState.agent?.agentId } ?: "",
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete(onBackClick)
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    installResultMessage?.let { message ->
        InstallResultDialog(
            message = message,
            onDismiss = { installResultMessage = null }
        )
    }
}
