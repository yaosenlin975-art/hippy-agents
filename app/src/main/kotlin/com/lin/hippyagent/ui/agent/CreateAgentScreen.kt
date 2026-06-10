package com.lin.hippyagent.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.chat.ModelSwitchSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAgentScreen(
    viewModel: AgentConfigViewModel,
    onBackClick: () -> Unit,
    onAgentCreated: (String) -> Unit,
    onNavigateToModelProvider: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showModelSelector by remember { mutableStateOf(false) }
    var showFallbackModelSelector by remember { mutableStateOf(false) }
    var showComplexModelSelector by remember { mutableStateOf(false) }
    var showDecisionModelSelector by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    // 初始技能选择（默认全选）
    val availableSkills = remember { viewModel.getAvailableSkills() }
    val selectedSkills = remember { mutableStateListOf<String>().also { it.addAll(availableSkills) } }

    // 初始工具开关
    val toolToggles = remember { mutableStateListOf<String>() }
    val toolDefs = remember { viewModel.getToolDefinitions() }

    LaunchedEffect(Unit) {
        viewModel.loadAgent()
        viewModel.refreshAvailableModels()
    }

    val canCreate by remember {
        derivedStateOf {
            uiState.agent?.modelName?.isNotEmpty() == true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_create), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item { Spacer(Modifier.height(16.dp)) }

            // ── 基本信息 Card ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.agent_basic_info), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }

                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.agent_name)) },
                            placeholder = { Text(stringResource(R.string.agent_name_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(stringResource(R.string.agent_default_model), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showModelSelector = true },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val agent = uiState.agent
                                Text(
                                    text = if (agent?.modelName?.isNotEmpty() == true) {
                                        val pName = uiState.providerNames[agent.modelProvider] ?: agent.modelProvider
                                        "$pName/${agent.modelName}"
                                    } else stringResource(R.string.agent_select_model_hint),
                                    fontSize = 14.sp,
                                    color = if (agent?.modelName?.isNotEmpty() == true) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.agent_fallback_model), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Card(modifier = Modifier.fillMaxWidth().clickable { showFallbackModelSelector = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = (uiState.agent?.fallbackModelName?.takeIf { it.isNotEmpty() }?.let { val p = uiState.providerNames[uiState.agent!!.fallbackModelProvider] ?: uiState.agent!!.fallbackModelProvider; "$p/$it" }) ?: stringResource(R.string.agent_fallback_model_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.Build, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.agent_complex_model), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Card(modifier = Modifier.fillMaxWidth().clickable { showComplexModelSelector = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = (uiState.agent?.complexModelName?.takeIf { it.isNotEmpty() }?.let { val p = uiState.providerNames[uiState.agent!!.complexModelProvider] ?: uiState.agent!!.complexModelProvider; "$p/$it" }) ?: stringResource(R.string.agent_complex_model_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.agent_decision_model), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Card(modifier = Modifier.fillMaxWidth().clickable { showDecisionModelSelector = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = (uiState.agent?.decisionModelName?.takeIf { it.isNotEmpty() }?.let { val p = uiState.providerNames[uiState.agent!!.decisionModelProvider] ?: uiState.agent!!.decisionModelProvider; "$p/$it" }) ?: stringResource(R.string.agent_decision_model_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // ── 初始技能选择 Card ──
            if (availableSkills.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.agent_initial_skills), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.agent_initial_skills_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                availableSkills.forEach { skill ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(skill, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Switch(
                                            checked = skill in selectedSkills,
                                            onCheckedChange = {
                                                if (it) selectedSkills.add(skill)
                                                else selectedSkills.remove(skill)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 初始工具开关 Card ──
            if (toolDefs.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.agent_tool_switch), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.agent_tool_switch_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                toolDefs.forEach { def ->
                                    val display = def.displayName.ifEmpty { def.name }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(display, fontSize = 14.sp)
                                            if (display != def.name) {
                                                Text(def.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Switch(
                                            checked = def.name !in toolToggles,
                                            onCheckedChange = { enabled ->
                                                if (enabled) toolToggles.remove(def.name)
                                                else toolToggles.add(def.name)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 创建按钮 ──
            item {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        viewModel.setInitialSkills(selectedSkills.toList())
                        viewModel.setInitialDisabledTools(toolToggles.toList())
                        viewModel.createAgent(name) { agentId ->
                            onAgentCreated(agentId)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    enabled = canCreate
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.agent_create), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showModelSelector) {
        ModelSwitchSheet(
            selectedModel = uiState.agent?.modelName ?: "",
            selectedProviderId = uiState.agent?.modelProvider,
            availableModels = uiState.availableModels,
            onModelSelected = { model, providerId ->
                viewModel.updateModelName(model)
                viewModel.updateModelProvider(providerId)
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
            selectedProviderId = uiState.agent?.fallbackModelProvider,
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
            selectedProviderId = uiState.agent?.complexModelProvider,
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
}
