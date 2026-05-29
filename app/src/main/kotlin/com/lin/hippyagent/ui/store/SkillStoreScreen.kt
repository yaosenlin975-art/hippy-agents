package com.lin.hippyagent.ui.store

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.skill.store.SkillSource
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.skill.store.StoreSkillItem

@Composable
fun SkillStoreScreen(
    viewModel: SkillStoreViewModel,
    isLinuxReady: Boolean = false,
    onBackClick: () -> Unit,
    onNavigateToEnvCheck: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // 自动检测并安装 Node.js 环境
    var nodeCheckState by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isLinuxReady) {
        if (isLinuxReady) {
            nodeCheckState = "checking"
            viewModel.checkAndPrepareNpx()
        }
    }
    val npxReady by viewModel.npxReady.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(npxReady) {
        if (npxReady) {
            nodeCheckState = "ready"
        } else if (nodeCheckState == "checking" && !isLinuxReady) {
            nodeCheckState = "failed"
        }
    }

    LaunchedEffect(uiState.nodeStatus) {
        val status = uiState.nodeStatus
        if (status != null && nodeCheckState != "ready") {
            nodeCheckState = if (status == "failed") "failed" else "installing"
        }
    }

    Scaffold(
        modifier = Modifier,
        topBar = {
            Column {
                // TopBar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                    Text(
                        stringResource(R.string.store_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                // 搜索栏
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    placeholder = { Text(stringResource(R.string.store_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Spacer(Modifier.height(8.dp))
                // 来源 Tab
                SourceTabRow(
                    activeSource = uiState.activeSource,
                    onSourceChange = viewModel::setSource
                )
                // 排序 Chip
                SortChipRow(
                    activeSort = uiState.sortType,
                    onSortChange = viewModel::setSortType
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // Node.js 环境安装状态提示
        if (nodeCheckState == "installing" || nodeCheckState == "checking") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (nodeCheckState == "installing") stringResource(R.string.store_installing_nodejs) else stringResource(R.string.store_checking_nodejs),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (nodeCheckState == "failed") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.store_nodejs_install_failed),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateToEnvCheck,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.store_go_to_env_check), fontSize = 12.sp)
                    }
                }
            }
        }
        if (uiState.isLoading && uiState.skills.isEmpty() && uiState.hotSkills.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.store_searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (uiState.error != null && uiState.skills.isEmpty()) {
            // 持久化错误状态（Linux 未就绪等）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.loadHotSkills() }) {
                        Text(stringResource(R.string.store_retry))
                    }
                }
            }
        } else if (nodeCheckState != null && nodeCheckState != "ready" && nodeCheckState != "checking" && nodeCheckState != "installing") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.store_no_skill_data), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.store_need_linux_nodejs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.loadHotSkills() }) {
                        Text(stringResource(R.string.store_reload))
                    }
                }
            }
        } else if (uiState.skills.isEmpty() && uiState.searchQuery.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.store_no_matching_skills), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.store_try_other_keywords),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // 热门推荐（仅无搜索时显示）
                if (uiState.searchQuery.isBlank() && uiState.hotSkills.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(R.string.store_hot_picks), Icons.Default.LocalFireDepartment)
                        HotSkillsRow(
                            skills = uiState.hotSkills,
                            installedIds = uiState.installedIds,
                            installingIds = uiState.installingIds,
                            onSkillClick = { viewModel.selectSkill(it) },
                            onInstall = viewModel::showInstallDialog
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                // 全部技能列表
                item { SectionHeader(stringResource(R.string.store_all_skills), Icons.Default.Sort) }
                items(
                    count = uiState.skills.size,
                    key = { uiState.skills[it].identifier }
                ) { index ->
                    val skill = uiState.skills[index]
                    val isInstalled = uiState.installedIds.contains(skill.identifier)
                    val isInstalling = uiState.installingIds.contains(skill.identifier)
                    StoreSkillCard(
                        skill = skill,
                        isInstalled = isInstalled,
                        isInstalling = isInstalling,
                        onInstall = { viewModel.showInstallDialog(skill) },
                        onClick = { viewModel.selectSkill(skill) }
                    )
                }
                if (uiState.isLoading) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    // 安装确认对话框
    uiState.showInstallDialog?.let { skill ->
        InstallConfirmDialog(
            skill = skill,
            isInstalled = uiState.installedIds.contains(skill.identifier),
            onConfirm = { viewModel.installSkill(skill) },
            onDismiss = viewModel::dismissInstallDialog
        )
    }

    // 技能详情 BottomSheet
    uiState.selectedSkill?.let { skill ->
        SkillDetailSheet(
            skill = skill,
            isInstalled = uiState.installedIds.contains(skill.identifier),
            isInstalling = uiState.installingIds.contains(skill.identifier),
            onInstall = {
                viewModel.selectSkill(null)
                viewModel.showInstallDialog(skill)
            },
            onDismiss = { viewModel.selectSkill(null) }
        )
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SourceTabRow(
    activeSource: SkillSource?,
    onSourceChange: (SkillSource?) -> Unit
) {
    val sources = listOf(null to stringResource(R.string.store_source_all)) + SkillSource.entries.map { it to it.displayName }
    val selectedIndex = sources.indexOfFirst { it.first == activeSource }.coerceAtLeast(0)

    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex])
                )
            }
        },
        divider = {}
    ) {
        sources.forEachIndexed { index, (source, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSourceChange(source) },
                text = { Text(label, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@Composable
fun SortChipRow(
    activeSort: SortType,
    onSortChange: (SortType) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(SortType.entries, key = { it.name }) { sort ->
            FilterChip(
                selected = sort == activeSort,
                onClick = { onSortChange(sort) },
                label = { Text(sort.displayName) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
fun HotSkillsRow(
    skills: List<StoreSkillItem>,
    installedIds: Set<String>,
    installingIds: Set<String>,
    onSkillClick: (StoreSkillItem) -> Unit,
    onInstall: (StoreSkillItem) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(skills, key = { it.identifier }) { skill ->
            HotSkillCard(
                skill = skill,
                isInstalled = installedIds.contains(skill.identifier),
                isInstalling = installingIds.contains(skill.identifier),
                onInstall = { onInstall(skill) },
                onClick = { onSkillClick(skill) }
            )
        }
    }
}

@Composable
fun HotSkillCard(
    skill: StoreSkillItem,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            // 来源徽章
            SourceBadge(skill.source)
            Spacer(Modifier.height(8.dp))
            Text(
                skill.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                skill.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (skill.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(String.format("%.1f", skill.rating), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(formatCount(skill.installCount), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun StoreSkillCard(
    skill: StoreSkillItem,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceBadge(skill.source)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            skill.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (skill.isValidated) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                // 安装按钮
                when {
                    isInstalling -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.store_installing), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    isInstalled -> {
                        OutlinedButton(onClick = {}, enabled = false) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(
                                     Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.store_installed))
                            }
                        }
                    }
                    else -> {
                        OutlinedButton(onClick = onInstall) {
                            Text(stringResource(R.string.agent_install))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                skill.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (skill.category.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                skill.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "by ${skill.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (skill.starsCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(formatCount(skill.starsCount), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(formatCount(skill.installCount), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SourceBadge(source: SkillSource) {
    val bgColor = Color(source.color)
    Text(
        text = source.displayName,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun InstallConfirmDialog(
    skill: StoreSkillItem,
    isInstalled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isInstalled) stringResource(R.string.store_reinstall) else stringResource(R.string.store_install_skill)) },
        text = {
            Column {
                Text(stringResource(R.string.store_install_confirm), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(skill.source)
                    Spacer(Modifier.width(8.dp))
                    Text(skill.name, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (!skill.isValidated) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.store_security_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.agent_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailSheet(
    skill: StoreSkillItem,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // 来源徽章 + 名称
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(skill.source)
                Spacer(Modifier.width(12.dp))
                Text(
                    skill.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            // 作者
            if (skill.author.isNotBlank()) {
                Text(
                    "by ${skill.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            // 统计数据行
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (skill.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            String.format("%.1f", skill.rating),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        formatCount(skill.installCount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (skill.starsCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "${formatCount(skill.starsCount)} stars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 安全认证
            if (skill.isValidated) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.store_security_passed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )

            Spacer(Modifier.height(12.dp))

            // 简介
            if (skill.description.isNotBlank()) {
                Text(
                    stringResource(R.string.store_intro),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
            }

            // 标签
            if (skill.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    skill.tags.take(5).forEach { tag ->
                        Text(
                            "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 分类 + 版本
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (skill.category.isNotBlank()) {
                    Text(
                        stringResource(R.string.store_category, skill.category),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (skill.version.isNotBlank()) {
                    Text(
                        stringResource(R.string.store_version, skill.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 安装按钮
            when {
                isInstalling -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.store_installing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                isInstalled -> {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(
                                    Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.store_installed))
                        }
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.agent_install))
                    }
                }
            }
        }
    }
}

