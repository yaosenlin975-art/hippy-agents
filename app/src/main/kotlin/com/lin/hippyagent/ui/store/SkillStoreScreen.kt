package com.lin.hippyagent.ui.store

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Sort
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.store.components.LoadingState
import com.lin.hippyagent.ui.store.components.ErrorState
import com.lin.hippyagent.ui.store.components.NoResultsState
import com.lin.hippyagent.ui.store.components.NodeNotReadyState
import com.lin.hippyagent.ui.store.components.HotSkillsRow
import com.lin.hippyagent.ui.store.components.InstallConfirmDialog
import com.lin.hippyagent.ui.store.components.NodeStatusBanner
import com.lin.hippyagent.ui.store.components.SearchBar
import com.lin.hippyagent.ui.store.components.SkillDetailSheet
import com.lin.hippyagent.ui.store.components.SortChipRow
import com.lin.hippyagent.ui.store.components.SourceTabRow
import com.lin.hippyagent.ui.store.components.ProviderChips
import com.lin.hippyagent.ui.store.components.StoreSkillCard

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

    var nodeCheckState by remember { mutableStateOf<NodeStatus>(NodeStatus.Unknown) }

    LaunchedEffect(isLinuxReady) {
        if (isLinuxReady) {
            nodeCheckState = NodeStatus.Checking
            viewModel.checkAndPrepareNpx()
        }
    }
    val npxReady by viewModel.npxReady.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(npxReady) {
        if (npxReady) {
            nodeCheckState = NodeStatus.Ready
        } else if (nodeCheckState == NodeStatus.Checking && !isLinuxReady) {
            nodeCheckState = NodeStatus.Failed
        }
    }

    LaunchedEffect(uiState.nodeStatus) {
        val status = uiState.nodeStatus
        if (status !is NodeStatus.Ready && nodeCheckState != NodeStatus.Ready) {
            nodeCheckState = when (status) {
                is NodeStatus.Failed -> NodeStatus.Failed
                is NodeStatus.Installing -> NodeStatus.Installing
                else -> nodeCheckState
            }
        }
    }

    Scaffold(
        modifier = Modifier,
        topBar = {
            Column {
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
                SearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChange
                )
                val providers by viewModel.providers.collectAsStateWithLifecycle()
                val selectedProviderKeys by viewModel.selectedProviderKeys.collectAsStateWithLifecycle()
                ProviderChips(
                    providers = providers,
                    selectedKeys = selectedProviderKeys,
                    onToggle = viewModel::toggleProvider
                )
                Spacer(Modifier.height(8.dp))
                SourceTabRow(
                    activeSource = uiState.activeSource,
                    onSourceChange = viewModel::setSource
                )
                SortChipRow(
                    activeSort = uiState.sortType,
                    onSortChange = viewModel::setSortType
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        NodeStatusBanner(
            nodeCheckState = nodeCheckState,
            onNavigateToEnvCheck = onNavigateToEnvCheck
        )

        when {
            uiState.isLoading && uiState.skills.isEmpty() && uiState.hotSkills.isEmpty() -> {
                LoadingState(padding)
            }
            uiState.error != null && uiState.skills.isEmpty() -> {
                ErrorState(
                    padding = padding,
                    error = uiState.error,
                    onRetry = { viewModel.loadHotSkills() }
                )
            }
            nodeCheckState != NodeStatus.Ready && nodeCheckState != NodeStatus.Checking && nodeCheckState != NodeStatus.Installing -> {
                NodeNotReadyState(
                    padding = padding,
                    onReload = { viewModel.loadHotSkills() }
                )
            }
            uiState.skills.isEmpty() && uiState.searchQuery.isNotBlank() -> {
                NoResultsState(padding)
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (uiState.searchQuery.isBlank() && uiState.hotSkills.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.store_hot_picks), Icons.Default.LocalFireDepartment)
                            HotSkillsRow(
                                skills = uiState.hotSkills,
                                installedIds = uiState.installedIds,
                                onSkillClick = { viewModel.selectSkill(it) },
                                onInstall = viewModel::showInstallDialog
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item { SectionHeader(stringResource(R.string.store_all_skills), Icons.Default.Sort) }
                    items(
                        count = uiState.skills.size,
                        key = { uiState.skills[it].identifier }
                    ) { index ->
                        val skill = uiState.skills[index]
                        val isInstalled = uiState.installedIds.contains(skill.identifier)
                        StoreSkillCard(
                            skill = skill,
                            isInstalled = isInstalled,
                            onInstall = { viewModel.showInstallDialog(skill) },
                            onClick = { viewModel.selectSkill(skill) }
                        )
                    }
                    if (uiState.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (uiState.hasMore) {
                        item {
                            TextButton(
                                onClick = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            ) {
                                Text(stringResource(R.string.store_load_more))
                            }
                        }
                    }
                    if (uiState.isLoading && !uiState.isLoadingMore) {
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
    }

    uiState.showInstallDialog?.let { skill ->
        InstallConfirmDialog(
            skill = skill,
            isInstalled = uiState.installedIds.contains(skill.identifier),
            installTarget = uiState.installTarget,
            onTargetChange = viewModel::setInstallTarget,
            onConfirm = { viewModel.installSkill(skill) },
            onDismiss = viewModel::dismissInstallDialog
        )
    }

    uiState.selectedSkill?.let { skill ->
        SkillDetailSheet(
            skill = skill,
            isInstalled = uiState.installedIds.contains(skill.identifier),
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
