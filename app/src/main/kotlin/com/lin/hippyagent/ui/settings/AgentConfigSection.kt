package com.lin.hippyagent.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lin.hippyagent.ui.agent.AgentConfigViewModel
import com.lin.hippyagent.ui.agent.AgentProfileFields
import com.lin.hippyagent.ui.agent.CoreFileEditorDialog
import com.lin.hippyagent.ui.agent.CoreFileItem
import com.lin.hippyagent.ui.agent.DeleteAgentDialog
import com.lin.hippyagent.ui.agent.InstallResultDialog
import com.lin.hippyagent.ui.agent.QuickActionButton
import com.lin.hippyagent.ui.agent.SkillsManagementSheet
import com.lin.hippyagent.ui.agent.ToolsManagementSheet
import com.lin.hippyagent.ui.agent.VerticalScrollbar
import com.lin.hippyagent.ui.chat.ModelSwitchSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigSection(
    viewModel: AgentConfigViewModel,
    agents: List<com.lin.hippyagent.core.agent.AgentProfile> = emptyList(),
    currentAgentId: String? = null,
    onSwitchAgent: (String) -> Unit = {},
    onDeleteAgent: (String) -> Unit = {},
    onNavigateToRunningConfig: (String) -> Unit = {},
    onNavigateToHeartbeat: (String) -> Unit = {},
    onNavigateToDream: (String) -> Unit = {},
    onNavigateToMCP: (String) -> Unit = {},
    onNavigateToACP: (String) -> Unit = {},
    onNavigateToCronJobs: (String) -> Unit = {},
    onNavigateToStore: () -> Unit = {},
    onNavigateToChannelConfig: (String) -> Unit = {},
    onNavigateToToolSecurity: (String) -> Unit = {},
    onNavigateToCommonMemory: (String?) -> Unit = {},
    onNavigateToModelProvider: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshAvailableModels()
        viewModel.loadAgent()
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModelSelector by remember { mutableStateOf(false) }
    var showFallbackModelSelector by remember { mutableStateOf(false) }
    var showComplexModelSelector by remember { mutableStateOf(false) }
    var showDecisionModelSelector by remember { mutableStateOf(false) }
    var editingFilename by remember { mutableStateOf<String?>(null) }
    var editingContent by remember { mutableStateOf("") }
    var isEditingContentLoaded by remember { mutableStateOf(false) }
    var memorySectionExpanded by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(true) }
    var basicInfoExpanded by remember { mutableStateOf(true) }
    var quickConfigExpanded by remember { mutableStateOf(true) }
    var filesExpanded by remember { mutableStateOf(true) }
    var showAgentSwitcher by remember { mutableStateOf(false) }
    var showSkillsSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.saveAvatarToInternalStorage(it) {} }
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
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val result = skillManager.installSkillFromZip(tempFile.absolutePath)
                    result.fold(
                        onSuccess = { skill -> installResultMessage = context.getString(R.string.agent_skill_installed, skill.name) },
                        onFailure = { e -> installResultMessage = context.getString(R.string.agent_skill_install_failed, e.message ?: "") }
                    )
                    tempFile.delete()
                } catch (e: Exception) {
                    installResultMessage = context.getString(R.string.agent_skill_install_failed, e.message ?: "")
                }
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    modifier = Modifier.weight(1f).clickable { isExpanded = !isExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.agent != null) {
                        Text(
                            text = uiState.agent!!.name.ifEmpty { uiState.agent!!.agentId },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (uiState.agent?.isDefault != true) {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.agent != null) {
                    val agent = uiState.agent!!

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        SectionHeader(
                            title = stringResource(R.string.agent_basic_info),
                            expanded = basicInfoExpanded,
                            onToggle = { basicInfoExpanded = !basicInfoExpanded }
                        )

                        AnimatedVisibility(visible = basicInfoExpanded) {
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
                                decisionModelName = agent.decisionModelName,
                                decisionModelProvider = agent.decisionModelProvider,
                                providerNames = uiState.providerNames,
                                onShowModelSelector = { showModelSelector = true },
                                onShowFallbackModelSelector = { showFallbackModelSelector = true },
                                onShowComplexModelSelector = { showComplexModelSelector = true },
                                onShowDecisionModelSelector = { showDecisionModelSelector = true },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { showAgentSwitcher = true }) {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = stringResource(R.string.agent_switch),
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showAgentSwitcher,
                                            onDismissRequest = { showAgentSwitcher = false }
                                        ) {
                                            agents.forEach { a ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            a.name.ifBlank { a.agentId },
                                                            fontWeight = if (a.agentId == currentAgentId) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    },
                                                    onClick = {
                                                        showAgentSwitcher = false
                                                        onSwitchAgent(a.agentId)
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Default.SmartToy,
                                                            contentDescription = null,
                                                            tint = if (a.agentId == currentAgentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        SectionHeader(
                            title = stringResource(R.string.agent_quick_config),
                            expanded = quickConfigExpanded,
                            onToggle = { quickConfigExpanded = !quickConfigExpanded }
                        )
                        AnimatedVisibility(visible = quickConfigExpanded) {
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
                                        icon = Icons.Default.Settings,
                                        label = stringResource(R.string.agent_tool_management),
                                        onClick = { showToolsSheet = true },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    QuickActionButton(
                                        icon = Icons.Default.Shield,
                                        label = stringResource(R.string.tool_security),
                                        onClick = { uiState.agent?.agentId?.let { onNavigateToToolSecurity(it) } },
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

                        SectionHeader(
                            title = stringResource(R.string.tool_cat_file),
                            expanded = filesExpanded,
                            onToggle = { filesExpanded = !filesExpanded }
                        )
                        AnimatedVisibility(visible = filesExpanded) {
                            val filesScrollState = rememberScrollState()
                            Box {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 300.dp)
                                        .verticalScroll(filesScrollState)
                                ) {
                                    val allMdFiles = (uiState.workspaceMdFiles + agent.coreFiles).distinct().sorted()
                                    val regularFiles = allMdFiles.filter { !it.startsWith("memory/") }
                                    val memoryFiles = allMdFiles.filter { it.startsWith("memory/") }

                                    regularFiles.forEach { filename ->
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
                                                Spacer(Modifier.width(12.dp))
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
                                VerticalScrollbar(
                                    scrollState = filesScrollState,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    if (showModelSelector) {
        ModelSwitchSheet(
            selectedModel = uiState.agent?.modelName ?: "",
            selectedProviderId = uiState.agent?.modelProvider,
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
            selectedModel = uiState.agent?.fallbackModelName ?: "",
            selectedProviderId = uiState.agent?.modelProvider,
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
            selectedModel = uiState.agent?.complexModelName ?: "",
            selectedProviderId = uiState.agent?.modelProvider,
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

    if (showDecisionModelSelector) {
        ModelSwitchSheet(
            selectedModel = uiState.agent?.decisionModelName ?: "",
            selectedProviderId = uiState.agent?.decisionModelProvider,
            availableModels = uiState.availableModels,
            onModelSelected = { model, providerId ->
                viewModel.updateDecisionModel(model, providerId)
                showDecisionModelSelector = false
            },
            onDismiss = { showDecisionModelSelector = false },
            onNavigateToSettings = {
                showDecisionModelSelector = false
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
            agentId = uiState.agent?.agentId ?: "",
            skills = uiState.agent?.skills ?: emptyList(),
            skillManager = skillManager,
            onUpdateSkills = { viewModel.updateSkills(it) },
            onNavigateToStore = onNavigateToStore,
            onInstallFromZip = { zipPickerLauncher.launch("application/zip") },
            onSyncResult = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
            onDismiss = { showSkillsSheet = false }
        )
    }

    if (showToolsSheet) {
        ToolsManagementSheet(
            agentId = uiState.agent?.agentId ?: "",
            disabledTools = uiState.agent?.disabledTools ?: emptyList(),
            onDismiss = { showToolsSheet = false }
        )
    }

    if (showDeleteDialog) {
        DeleteAgentDialog(
            agentName = uiState.agent?.name?.ifEmpty { uiState.agent?.agentId } ?: "",
            onConfirm = {
                showDeleteDialog = false
                val deletedId = uiState.agent?.agentId ?: return@DeleteAgentDialog
                viewModel.delete {
                    onDeleteAgent(deletedId)
                }
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

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}
