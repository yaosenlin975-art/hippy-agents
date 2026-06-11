package com.lin.hippyagent.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.skill.SkillInfo
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.SkillVisibility
import com.lin.hippyagent.core.skill.WorkspaceSkillConfigManagerRegistry
import com.lin.hippyagent.data.repository.AgentRepository
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSkillScreen(
    agentId: String,
    skillManager: SkillManager,
    agentRepository: AgentRepository,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val allSkills = remember(refreshTrigger) {
        val publicSkills = skillManager.listSkills()
        val workspaceSkills = mutableSetOf<String>()
        val workspaceDir = com.lin.hippyagent.core.storage.StorageManager(context).getWorkingDir()
            ?.resolve("workspaces/$agentId/skills")
        if (workspaceDir?.exists() == true) {
            workspaceDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.resolve("SKILL.md").exists()) {
                    workspaceSkills.add(dir.name)
                }
            }
        }
        val mergedSkills = publicSkills.toMutableList()
        workspaceSkills.forEach { wsId ->
            if (mergedSkills.none { it.id == wsId }) {
                val wsDir = com.lin.hippyagent.core.storage.StorageManager(context)
                    .getWorkingDir()?.resolve("workspaces/$agentId/skills/$wsId")
                if (wsDir?.resolve("SKILL.md")?.exists() == true) {
                    try {
                        val info = skillManager.parseSkillInfo(wsDir)
                        mergedSkills.add(info)
                    } catch (_: Exception) {}
                }
            }
        }
        mergedSkills
    }
    val coroutineScope = rememberCoroutineScope()
    val configRegistry = org.koin.compose.koinInject<WorkspaceSkillConfigManagerRegistry>()
    val skillConfig = remember(agentId) { configRegistry.forAgent(agentId) }

    // 从 AgentProfile 加载已绑定的技能
    var enabledSkills by remember { mutableStateOf(setOf<String>()) }
    var skillsLoaded by remember { mutableStateOf(false) }

    // 全局技能启用/禁用状态
    var globalSkillStates by remember { mutableStateOf(mapOf<String, Boolean>()) }

    // 4 态可见范围(per-agent),与 skill_config.json 同步
    var visibilities by remember { mutableStateOf(mapOf<String, SkillVisibility>()) }

    LaunchedEffect(agentId) {
        agentRepository.getProfiles().collect { profiles ->
            val profile = profiles[agentId]
            if (profile != null) {
                enabledSkills = profile.skills.toSet()
                skillsLoaded = true
            }
        }
    }

    // 加载全局技能状态
    LaunchedEffect(Unit) {
        val states = allSkills.associate { skill -> skill.id to skillManager.isSkillEnabled(skill.id) }
        globalSkillStates = states
    }

    // 加载每技能 4 态可见范围
    LaunchedEffect(agentId, allSkills) {
        visibilities = allSkills.associate { skill -> skill.id to skillConfig.getSkillVisibility(skill.id) }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.agent_skills_title),
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        if (allSkills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.chat_no_agent_skills_available), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.agent_install_skills_first), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allSkills, key = { it.id }) { skill ->
                    val isGloballyEnabled = globalSkillStates[skill.id] ?: true
                    val isBoundToAgent = skill.id in enabledSkills
                    val visibility = visibilities[skill.id] ?: SkillVisibility.ALL
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(skill.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(skill.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // 全局启用/禁用 Switch
                                Switch(
                                    checked = isGloballyEnabled,
                                    onCheckedChange = { enabled ->
                                        globalSkillStates = globalSkillStates + (skill.id to enabled)
                                        skillManager.setSkillEnabled(skill.id, enabled)
                                        refreshTrigger++
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                // Per-agent 绑定 Checkbox
                                Checkbox(
                                    checked = isBoundToAgent,
                                    onCheckedChange = { checked ->
                                        val newSkills = if (checked) {
                                            enabledSkills + skill.id
                                        } else {
                                            enabledSkills - skill.id
                                        }
                                        enabledSkills = newSkills
                                        coroutineScope.launch {
                                            agentRepository.saveSkills(agentId, newSkills.toList())
                                                .onFailure { Timber.e(it, "Failed to save skills for $agentId") }
                                        }
                                    }
                                )
                            }
                            if (isBoundToAgent) {
                                Spacer(Modifier.height(8.dp))
                                SkillVisibilityPicker(
                                    value = visibility,
                                    onValueChange = { newVis ->
                                        visibilities = visibilities + (skill.id to newVis)
                                        skillConfig.setSkillVisibility(skill.id, newVis)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
