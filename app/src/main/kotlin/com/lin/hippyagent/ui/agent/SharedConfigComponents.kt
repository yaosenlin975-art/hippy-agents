package com.lin.hippyagent.ui.agent

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lin.hippyagent.core.skill.SkillInfo
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.SkillVisibility
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.ui.components.getAvatarIcon
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R

@Composable
fun ModelSelectorRow(
    label: String,
    modelText: String,
    placeholder: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (modelText.isNotEmpty()) modelText else placeholder,
                    fontSize = 14.sp,
                    color = if (modelText.isNotEmpty()) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun AgentProfileFields(
    avatarUrl: String?,
    onPickImage: () -> Unit,
    onResetAvatar: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modelName: String,
    modelProvider: String,
    fallbackModelName: String,
    fallbackModelProvider: String,
    complexModelName: String,
    complexModelProvider: String,
    decisionModelName: String = "",
    decisionModelProvider: String = "",
    providerNames: Map<String, String>,
    onShowModelSelector: () -> Unit,
    onShowFallbackModelSelector: () -> Unit,
    onShowComplexModelSelector: () -> Unit,
    onShowDecisionModelSelector: () -> Unit = {},
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isImageAvatar = avatarUrl != null && avatarUrl.isNotEmpty()
        && (avatarUrl.startsWith("/") || avatarUrl.startsWith("content://"))
    var showAvatarMenu by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    if (isImageAvatar) {
                        val model = avatarUrl?.let { path ->
                            if (path.startsWith("/")) java.io.File(path)
                            else android.net.Uri.parse(path)
                        }
                        AsyncImage(
                            model = model,
                            contentDescription = stringResource(R.string.conversation_avatar),
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showAvatarMenu = true },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { showAvatarMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = stringResource(R.string.conversation_avatar),
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showAvatarMenu,
                        onDismissRequest = { showAvatarMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_avatar_from_gallery)) },
                            onClick = {
                                showAvatarMenu = false
                                onPickImage()
                            },
                            leadingIcon = { Icon(Icons.Default.Image, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_avatar_default)) },
                            onClick = {
                                showAvatarMenu = false
                                onResetAvatar()
                            },
                            leadingIcon = { Icon(Icons.Default.Close, null) }
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = { Text(stringResource(R.string.agent_nickname_hint)) },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                if (trailingContent != null) {
                    trailingContent()
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_enable), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text(stringResource(R.string.agent_enable_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(Modifier.height(12.dp))
            ModelSelectorRow(
                label = stringResource(R.string.agent_default_model),
                modelText = if (modelName.isNotEmpty()) {
                    val p = providerNames[modelProvider] ?: modelProvider
                    "$p/$modelName"
                } else "",
                placeholder = stringResource(R.string.agent_select_model_hint),
                icon = Icons.Default.Settings,
                onClick = onShowModelSelector
            )

            Spacer(Modifier.height(8.dp))
            ModelSelectorRow(
                label = stringResource(R.string.agent_fallback_model),
                modelText = if (fallbackModelName.isNotEmpty()) {
                    val p = providerNames[fallbackModelProvider] ?: fallbackModelProvider
                    "$p/$fallbackModelName"
                } else "",
                placeholder = stringResource(R.string.agent_fallback_model_hint),
                icon = Icons.Default.Build,
                onClick = onShowFallbackModelSelector
            )

            Spacer(Modifier.height(8.dp))
            ModelSelectorRow(
                label = stringResource(R.string.agent_complex_model),
                modelText = if (complexModelName.isNotEmpty()) {
                    val p = providerNames[complexModelProvider] ?: complexModelProvider
                    "$p/$complexModelName"
                } else "",
                placeholder = stringResource(R.string.agent_complex_model_hint),
                icon = Icons.Default.Tune,
                onClick = onShowComplexModelSelector
            )

            Spacer(Modifier.height(8.dp))
            ModelSelectorRow(
                label = stringResource(R.string.agent_decision_model),
                modelText = if (decisionModelName.isNotEmpty()) {
                    val p = providerNames[decisionModelProvider] ?: decisionModelProvider
                    "$p/$decisionModelName"
                } else "",
                placeholder = stringResource(R.string.agent_decision_model_hint),
                icon = Icons.Default.Psychology,
                onClick = onShowDecisionModelSelector
            )
        }
    }
}

/**
 * 4 态可见范围选择器:Off | Chat | Work | All
 * 替换原 On/Off Switch;持久化到 [WorkspaceSkillConfigManager.setSkillVisibility] / [setToolVisibility]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillVisibilityPicker(
    value: SkillVisibility,
    onValueChange: (SkillVisibility) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        SkillVisibility.OFF to stringResource(R.string.agent_skill_visibility_off),
        SkillVisibility.CHAT to stringResource(R.string.agent_skill_visibility_chat),
        SkillVisibility.WORK to stringResource(R.string.agent_skill_visibility_work),
        SkillVisibility.ALL to stringResource(R.string.agent_skill_visibility_all)
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, (visibility, label) ->
            SegmentedButton(
                selected = value == visibility,
                onClick = { onValueChange(visibility) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * 已废弃:仅保留给全局 [com.lin.hippyagent.ui.settings.ToolsListScreen] 使用。
 * 新位置(per-agent 场景)统一用 [SkillVisibilityPicker],4 态且持久化。
 */
@Deprecated("Use SkillVisibilityPicker for per-agent scope; this is UI-only and not persisted")
@Composable
fun ModeVisibilityChips(
    visible: Set<String>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        listOf("Chat", "Work").forEach { mode ->
            androidx.compose.material3.FilterChip(
                selected = mode in visible,
                onClick = {
                    val newSet = if (mode in visible) visible - mode else visible + mode
                    onChange(newSet)
                },
                label = { Text(mode, fontSize = 10.sp) },
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagementSheet(
    agentId: String,
    skills: List<String>,
    skillManager: SkillManager,
    onUpdateSkills: (List<String>) -> Unit,
    onNavigateToStore: () -> Unit,
    onInstallFromZip: () -> Unit,
    onSyncResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var allPoolSkills by remember { mutableStateOf(skillManager.listSkills()) }
    var showLoadFromPoolSheet by remember { mutableStateOf(false) }
    var selectedSkillDetail by remember { mutableStateOf<SkillInfo?>(null) }
    var skillSearchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val configRegistry: com.lin.hippyagent.core.skill.WorkspaceSkillConfigManagerRegistry = org.koin.compose.koinInject()
    val skillConfig = remember(agentId) { configRegistry.forAgent(agentId) }
    var visibilities by remember { mutableStateOf<Map<String, SkillVisibility>>(emptyMap()) }
    // 记录每个 skill 上次「非 OFF」的可见范围,详情弹窗里切回 ON 时恢复
    // 仅在 toggle 事件中更新,LaunchedEffect 重算时不能覆盖(否则丢失切 OFF 前缓存)
    var lastEnabledVisibility by remember { mutableStateOf<Map<String, SkillVisibility>>(emptyMap()) }
    LaunchedEffect(agentId, allPoolSkills) {
        visibilities = buildMap {
            for (id in skills) put(id, skillConfig.getSkillVisibility(id))
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!showLoadFromPoolSheet) onDismiss()
        },
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.skill_management),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = skillSearchQuery,
                onValueChange = { skillSearchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text(stringResource(R.string.agent_search_skills), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    Triple(Icons.Default.Store, stringResource(R.string.agent_store)) { onNavigateToStore() },
                    Triple(Icons.Default.FileUpload, stringResource(R.string.agent_install)) { onInstallFromZip() },
                    Triple(Icons.Default.Extension, stringResource(R.string.agent_load)) { showLoadFromPoolSheet = true },
                    Triple(Icons.Default.Save, stringResource(R.string.agent_sync)) {
                        val poolSkillIds = allPoolSkills.map { it.id }.toSet()
                        val uniqueSkills = skills.filter { it !in poolSkillIds }
                        onSyncResult(
                            if (uniqueSkills.isNotEmpty()) context.getString(R.string.agent_skills_synced, uniqueSkills.size) else context.getString(R.string.agent_no_skills_to_sync)
                        )
                    }
                ).forEach { (icon, label, action) ->
                    TextButton(
                        onClick = { action() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(icon, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val skillMap = remember(allPoolSkills) { allPoolSkills.associateBy { it.id } }
            val filteredSkills = if (skillSearchQuery.isBlank()) skills else skills.filter { skillId ->
                val info = skillMap[skillId]
                skillId.contains(skillSearchQuery, ignoreCase = true) ||
                    (info?.name?.contains(skillSearchQuery, ignoreCase = true) == true) ||
                    (info?.description?.contains(skillSearchQuery, ignoreCase = true) == true)
            }
            if (skills.isEmpty()) {
                Text(text = stringResource(R.string.no_skills), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filteredSkills, key = { it }) { skillId ->
                        val skillInfo = skillMap[skillId]
                        val visibility = visibilities[skillId] ?: SkillVisibility.ALL
                        val isOn = visibility != SkillVisibility.OFF
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { selectedSkillDetail = skillInfo },
                            colors = CardDefaults.cardColors(containerColor = if (isOn) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = skillInfo?.displayNameOrName() ?: skillId,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isOn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                    if (skillInfo != null) {
                                        Text(
                                            text = skillInfo.description,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "v${skillInfo.version}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                SkillVisibilityPicker(
                                    value = visibility,
                                    onValueChange = { newVis ->
                                        visibilities = visibilities + (skillId to newVis)
                                        skillConfig.setSkillVisibility(skillId, newVis)
                                    }
                                )
                                IconButton(
                                    onClick = {
                                        lastEnabledVisibility = lastEnabledVisibility - skillId
                                        visibilities = visibilities - skillId
                                        onUpdateSkills(skills - skillId)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.agent_delete_skill),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        selectedSkillDetail?.let { skill ->
            SkillDetailDialog(
                skill = skill,
                skillManager = skillManager,
                onDismiss = { selectedSkillDetail = null },
                fromAgentConfig = true,
                isSkillEnabled = (visibilities[skill.id] ?: SkillVisibility.ALL) != SkillVisibility.OFF,
                onToggleSkill = { id, enabled ->
                    if (enabled) {
                        // 切回 ON: 恢复上次的非 OFF 可见范围(默认 ALL)
                        val restore = lastEnabledVisibility[id] ?: SkillVisibility.ALL
                        visibilities = visibilities + (id to restore)
                        skillConfig.setSkillVisibility(id, restore)
                    } else {
                        // 切到 OFF 前先记住当前可见范围,便于下次恢复
                        val current = visibilities[id] ?: SkillVisibility.ALL
                        if (current != SkillVisibility.OFF) {
                            lastEnabledVisibility = lastEnabledVisibility + (id to current)
                        }
                        visibilities = visibilities + (id to SkillVisibility.OFF)
                        skillConfig.setSkillVisibility(id, SkillVisibility.OFF)
                    }
                }
            )
        }

        if (showLoadFromPoolSheet) {
            LoadFromPoolSheet(
                allPoolSkills = allPoolSkills,
                agentSkillIds = skills.toSet(),
                skillManager = skillManager,
                onLoadSkill = { skillId ->
                    onUpdateSkills(skills + skillId)
                    allPoolSkills = skillManager.listSkills()
                },
                onDismiss = { showLoadFromPoolSheet = false }
            )
        }
    }
}

@Composable
fun SkillDetailDialog(
    skill: SkillInfo,
    skillManager: SkillManager,
    onDismiss: () -> Unit,
    fromAgentConfig: Boolean = false,
    isSkillEnabled: Boolean = true,
    onToggleSkill: ((skillId: String, enabled: Boolean) -> Unit)? = null
) {
    var skillMdContent by remember(skill.id) { mutableStateOf(skill.description) }
    LaunchedEffect(skill.id) {
        withContext(Dispatchers.IO) {
            skillMdContent = runCatching {
                skillManager.getSkillDir(skill.id)
                    ?.resolve("SKILL.md")
                    ?.takeIf { it.exists() }
                    ?.readText()
            }.getOrNull() ?: skill.description
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.displayNameOrName(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("v${skill.version}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (fromAgentConfig && onToggleSkill != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.agent_enable_skill), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                        Switch(
                            checked = isSkillEnabled,
                            onCheckedChange = { enabled -> onToggleSkill(skill.id, enabled) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.surface,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = skillMdContent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadFromPoolSheet(
    allPoolSkills: List<SkillInfo>,
    agentSkillIds: Set<String>,
    skillManager: SkillManager,
    onLoadSkill: (String) -> Unit,
    onDismiss: () -> Unit
) {
    data class PoolSkillEntry(
        val skill: SkillInfo,
        val isUpdate: Boolean,
        val agentVersion: String?
    )

    val context = LocalContext.current

    val newSkills = allPoolSkills.filter { it.id !in agentSkillIds }
        .map { PoolSkillEntry(it, false, null) }
    val updatableSkills = allPoolSkills.filter { it.id in agentSkillIds }.mapNotNull { poolSkill ->
        val agentSkillInfo = skillManager.getSkill(poolSkill.id)
        val agentVer = agentSkillInfo?.version
        if (agentVer != null && agentVer != poolSkill.version) {
            PoolSkillEntry(poolSkill, true, agentVer)
        } else null
    }
    val loadableSkills = newSkills + updatableSkills

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.agent_load_from_pool),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            if (loadableSkills.isEmpty()) {
                Text(
                    text = stringResource(R.string.agent_no_skills_in_pool),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(loadableSkills, key = { it.skill.id }) { entry ->
                        val skill = entry.skill
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    if (!entry.isUpdate) {
                                        onLoadSkill(skill.id)
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = skill.displayNameOrName(),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        if (entry.isUpdate) {
                                            Text(
                                                text = context.getString(R.string.agent_skill_update_available, entry.agentVersion ?: "", skill.version),
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.agent_new),
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }
                                    Text(
                                        text = skill.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "v${skill.version}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Icon(
                                    imageVector = if (entry.isUpdate) Icons.Default.FileUpload else Icons.Default.Add,
                                    contentDescription = if (entry.isUpdate) stringResource(R.string.agent_update) else stringResource(R.string.agent_load),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsManagementSheet(
    agentId: String,
    disabledTools: List<String>,
    onDismiss: () -> Unit
) {
    val toolRegistry = org.koin.compose.koinInject<ToolRegistry>()
    val configRegistry: com.lin.hippyagent.core.skill.WorkspaceSkillConfigManagerRegistry = org.koin.compose.koinInject()
    val toolConfig = remember(agentId) { configRegistry.forAgent(agentId) }
    val allTools = remember { toolRegistry.getVisibleDefinitions() }
    var toolSearchQuery by remember { mutableStateOf("") }
    var selectedTool by remember { mutableStateOf<ToolDefinition?>(null) }
    var visibilities by remember { mutableStateOf<Map<String, SkillVisibility>>(emptyMap()) }
    LaunchedEffect(agentId, allTools) {
        visibilities = buildMap {
            for (def in allTools) put(def.name, toolConfig.getToolVisibility(def.name))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.agent_tool_management),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = toolSearchQuery,
                onValueChange = { toolSearchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text(stringResource(R.string.agent_search_tools), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            val filteredTools = if (toolSearchQuery.isBlank()) allTools else allTools.filter { def ->
                def.name.contains(toolSearchQuery, ignoreCase = true) ||
                    def.displayName.contains(toolSearchQuery, ignoreCase = true) ||
                    def.description.contains(toolSearchQuery, ignoreCase = true)
            }

            if (filteredTools.isEmpty()) {
                Text(text = stringResource(R.string.agent_no_tools), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filteredTools, key = { it.name }) { toolDef ->
                        val visibility = visibilities[toolDef.name] ?: SkillVisibility.ALL
                        val isOn = visibility != SkillVisibility.OFF
                        Card(
                            onClick = { selectedTool = toolDef },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isOn) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = toolDef.displayName.ifEmpty { toolDef.name },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isOn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        text = toolDef.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                SkillVisibilityPicker(
                                    value = visibility,
                                    onValueChange = { newVis ->
                                        visibilities = visibilities + (toolDef.name to newVis)
                                        toolConfig.setToolVisibility(toolDef.name, newVis)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedTool?.let { tool ->
        ToolDetailDialog(
            tool = tool,
            onDismiss = { selectedTool = null }
        )
    }
}

@Composable
fun ToolDetailDialog(
    tool: ToolDefinition,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tool.name, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                Text(
                    text = tool.description,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tool.parameters.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.agent_params), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    tool.parameters.values.forEach { param ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = param.name,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = " (${param.type})",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (param.required) {
                                Text(text = " *", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (param.description.isNotBlank()) {
                            Text(
                                text = param.description,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@Composable
fun DeleteAgentDialog(
    agentName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_confirm_delete)) },
        text = { Text(context.getString(R.string.agent_delete_confirm, agentName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun InstallResultDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_install_result)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreFileEditorDialog(
    filename: String,
    content: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var editedContent by remember { mutableStateOf(content) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = filename, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onSave(editedContent) }) { Text(stringResource(R.string.save)) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editedContent,
                onValueChange = { editedContent = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                maxLines = 50
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(vertical = 2.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
fun CoreFileItem(
    filename: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(filename, fontSize = 14.sp)
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.agent_delete_file)) },
            text = { Text(context.getString(R.string.agent_delete_file_confirm, filename)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
fun VerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val canScroll = scrollState.maxValue > 0
    if (!canScroll) return

    val thumbFraction = scrollState.viewportSize.toFloat() / (scrollState.maxValue + scrollState.viewportSize)
    val scrollFraction = scrollState.value.toFloat() / scrollState.maxValue
    val thumbHeightDp = with(density) { (thumbFraction * scrollState.viewportSize).toDp().coerceAtLeast(20.dp) }
    val maxOffsetDp = with(density) { (scrollState.viewportSize - thumbFraction * scrollState.viewportSize).toDp() }
    val thumbOffsetDp = maxOffsetDp * scrollFraction

    Box(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .offset(y = thumbOffsetDp)
                .width(4.dp)
                .height(thumbHeightDp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}
