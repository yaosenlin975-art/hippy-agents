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
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.ui.components.getAvatarIcon
import com.lin.hippyagent.ui.components.AVATAR_ICONS

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
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        Card(
            modifier = Modifier.weight(1f).clickable(onClick = onClick),
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
    providerNames: Map<String, String>,
    onShowModelSelector: () -> Unit,
    onShowFallbackModelSelector: () -> Unit,
    onShowComplexModelSelector: () -> Unit,
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
                            contentDescription = "头像",
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
                                contentDescription = "头像",
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
                            text = { Text("从相册选择") },
                            onClick = {
                                showAvatarMenu = false
                                onPickImage()
                            },
                            leadingIcon = { Icon(Icons.Default.Image, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("使用默认头像") },
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
                    placeholder = { Text("输入昵称") },
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
                    Text("启用", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text("关闭后该智能体不在后台运行", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                label = "默认模型",
                modelText = if (modelName.isNotEmpty()) {
                    val p = providerNames[modelProvider] ?: modelProvider
                    "$p/$modelName"
                } else "",
                placeholder = "点击选择模型",
                icon = Icons.Default.Settings,
                onClick = onShowModelSelector
            )

            Spacer(Modifier.height(8.dp))
            ModelSelectorRow(
                label = "备用模型",
                modelText = if (fallbackModelName.isNotEmpty()) {
                    val p = providerNames[fallbackModelProvider] ?: fallbackModelProvider
                    "$p/$fallbackModelName"
                } else "",
                placeholder = "未设置（主模型失败时使用）",
                icon = Icons.Default.Build,
                onClick = onShowFallbackModelSelector
            )

            Spacer(Modifier.height(8.dp))
            ModelSelectorRow(
                label = "复杂模型",
                modelText = if (complexModelName.isNotEmpty()) {
                    val p = providerNames[complexModelProvider] ?: complexModelProvider
                    "$p/$complexModelName"
                } else "",
                placeholder = "未设置（复杂任务自动路由）",
                icon = Icons.Default.Tune,
                onClick = onShowComplexModelSelector
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagementSheet(
    skills: List<String>,
    disabledSkills: List<String>,
    skillManager: SkillManager,
    onToggleSkillEnabled: (String, Boolean) -> Unit,
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
                text = "技能管理",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = skillSearchQuery,
                onValueChange = { skillSearchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("搜索技能...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    Triple(Icons.Default.Store, "商店") { onNavigateToStore() },
                    Triple(Icons.Default.FileUpload, "安装") { onInstallFromZip() },
                    Triple(Icons.Default.Extension, "载入") { showLoadFromPoolSheet = true },
                    Triple(Icons.Default.Save, "同步") {
                        val poolSkillIds = allPoolSkills.map { it.id }.toSet()
                        val uniqueSkills = skills.filter { it !in poolSkillIds }
                        onSyncResult(
                            if (uniqueSkills.isNotEmpty()) "已同步 ${uniqueSkills.size} 个技能到技能池" else "没有需要同步的技能"
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
                Text(text = "暂无技能", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filteredSkills, key = { it }) { skillId ->
                        val skillInfo = skillMap[skillId]
                        val isEnabled = skillId !in disabledSkills
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { selectedSkillDetail = skillInfo },
                            colors = CardDefaults.cardColors(containerColor = if (isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { enabled -> onToggleSkillEnabled(skillId, enabled) },
                                    modifier = Modifier.height(24.dp)
                                )
                                IconButton(
                                    onClick = {
                                        onUpdateSkills(skills - skillId)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除技能",
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
                isSkillEnabled = skill.id !in disabledSkills,
                onToggleSkill = onToggleSkillEnabled
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
            skillMdContent = skillManager.getSkillDir(skill.id)
                ?.resolve("SKILL.md")
                ?.readText()
                ?: skill.description
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
                        Text("启用此技能", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
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
            TextButton(onClick = onDismiss) { Text("关闭") }
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
                text = "从技能池载入",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            if (loadableSkills.isEmpty()) {
                Text(
                    text = "技能池中没有可载入或更新的技能",
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
                                                text = "可更新 ${entry.agentVersion} → v${skill.version}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Text(
                                                text = "新",
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
                                    contentDescription = if (entry.isUpdate) "更新" else "载入",
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
    disabledTools: List<String>,
    onToggleToolEnabled: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val toolRegistry = org.koin.compose.koinInject<ToolRegistry>()
    val allTools = remember { toolRegistry.getVisibleDefinitions() }
    var toolSearchQuery by remember { mutableStateOf("") }
    var selectedTool by remember { mutableStateOf<ToolDefinition?>(null) }

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
                text = "工具管理",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = toolSearchQuery,
                onValueChange = { toolSearchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("搜索工具...", fontSize = 13.sp) },
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
                Text(text = "暂无工具", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filteredTools, key = { it.name }) { toolDef ->
                        val isEnabled = toolDef.name !in disabledTools
                        Card(
                            onClick = { selectedTool = toolDef },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { enabled -> onToggleToolEnabled(toolDef.name, enabled) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary
                                    )
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
                    Text("参数", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun DeleteAgentDialog(
    agentName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = { Text("确定要删除智能体「$agentName」吗？\n\n注意：智能体的工作区数据文件夹不会被删除，仅移除配置。此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
        title = { Text("安装结果") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
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
                TextButton(onClick = { onSave(editedContent) }) { Text("保存") }
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
                TextButton(onClick = onDismiss) { Text("取消") }
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
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件") },
            text = { Text("确定要删除 $filename 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
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
