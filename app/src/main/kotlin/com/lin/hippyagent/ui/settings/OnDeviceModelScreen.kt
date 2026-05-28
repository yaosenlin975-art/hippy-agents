package com.lin.hippyagent.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.core.ondevice.BackendPreference
import com.lin.hippyagent.core.ondevice.DownloadProgress
import com.lin.hippyagent.core.ondevice.DownloadState
import com.lin.hippyagent.core.ondevice.EngineState
import com.lin.hippyagent.core.ondevice.HuggingFaceModel
import com.lin.hippyagent.core.ondevice.OnDeviceCapability
import com.lin.hippyagent.core.ondevice.OnDeviceModelConfig
import com.lin.hippyagent.core.ondevice.OnDeviceModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDeviceModelScreen(
    viewModel: OnDeviceModelViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDownloadSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Huggingface", "模型管理")

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) viewModel.loadPopularHuggingFaceModels()
    }

    val activeDownloads by remember(uiState.downloadProgress, uiState.modelStates) {
        derivedStateOf {
            uiState.modelStates.filter { (_, state) ->
                state.downloadState == DownloadState.DOWNLOADING || state.downloadState == DownloadState.PAUSED
            }.map { (id, state) ->
                DownloadItem(id, state, uiState.downloadProgress[id])
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("端侧模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (activeDownloads.isNotEmpty()) {
                        IconButton(onClick = { showDownloadSheet = true }) {
                            BadgedBox(
                                badge = {
                                    Text(
                                        text = if (activeDownloads.size > 9) "9+" else activeDownloads.size.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "下载管理")
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> HuggingFaceTab(
                    uiState = uiState,
                    onSearch = { viewModel.searchHuggingFace(it) },
                    onDownloadHfModel = { viewModel.downloadHuggingFaceModel(it) },
                )
                1 -> ModelManagementTab(
                    uiState = uiState,
                    onDownload = { viewModel.downloadModel(it) },
                    onPause = { viewModel.pauseDownload(it) },
                    onDelete = { viewModel.deleteModel(it) },
                    onLoadEngine = { id, backend -> viewModel.loadEngine(id, backend) },
                    onUnloadEngine = { viewModel.unloadEngine() },
                    onSetAutoUnload = { viewModel.setAutoUnload(it) },
                )
            }
        }
    }

    if (showDownloadSheet) {
        DownloadManagementSheet(
            downloads = activeDownloads,
            availableModels = uiState.availableModels,
            onPause = { viewModel.pauseDownload(it) },
            onResume = { viewModel.downloadModel(it) },
            onDismiss = { showDownloadSheet = false },
        )
    }
}

@Composable
private fun HuggingFaceTab(
    uiState: OnDeviceModelUiState,
    onSearch: (String) -> Unit,
    onDownloadHfModel: (HuggingFaceModel) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索 Huggingface 模型...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
        )

        if (uiState.hfLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            items(uiState.hfModels, key = { it.displayName }) { model ->
                HuggingFaceModelCard(model = model, onDownload = { onDownloadHfModel(model) })
            }

            if (uiState.hfModels.isEmpty() && !uiState.hfLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("输入关键词搜索模型", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HuggingFaceModelCard(model: HuggingFaceModel, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = model.pipeline_tag ?: "unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatDownloads(model.downloads),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val displayTags = model.tags.filter { it in setOf("onnx", "safetensors", "gguf", "pytorch", "text-generation", "text2text-generation", "feature-extraction") }.take(4)
                    if (displayTags.isNotEmpty()) {
                        Text(
                            text = displayTags.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "下载",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun formatDownloads(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

@Composable
private fun ModelManagementTab(
    uiState: OnDeviceModelUiState,
    onDownload: (String) -> Unit,
    onPause: (String) -> Unit,
    onDelete: (String) -> Unit,
    onLoadEngine: (String, BackendPreference) -> Unit,
    onUnloadEngine: () -> Unit,
    onSetAutoUnload: (Boolean) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredModels by remember(uiState.availableModels, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) uiState.availableModels
            else {
                val q = searchQuery.lowercase()
                uiState.availableModels.filter { model ->
                    model.name.lowercase().contains(q) ||
                        model.description.lowercase().contains(q) ||
                        model.id.lowercase().contains(q)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索模型名称或描述...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        val downloadableModels = remember(filteredModels, uiState.modelStates) {
            filteredModels.filter { config ->
                val state = uiState.modelStates[config.id]
                state?.downloadState == DownloadState.DOWNLOADED ||
                    state?.downloadState == DownloadState.DOWNLOADING ||
                    state?.downloadState == DownloadState.PAUSED
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            items(downloadableModels, key = { it.id }) { config ->
            val state = uiState.modelStates[config.id]
            val progress = uiState.downloadProgress[config.id]
            val isEngineLoaded = uiState.currentEngineModelId == config.id &&
                state?.engineState == EngineState.LOADED

            ModelCard(
                config = config,
                state = state,
                progress = progress,
                isEngineLoaded = isEngineLoaded,
                isLoading = uiState.isLoading,
                onDownload = { onDownload(config.id) },
                onPause = { onPause(config.id) },
                onDelete = { onDelete(config.id) },
                onLoadEngine = { backend -> onLoadEngine(config.id, backend) },
                onUnloadEngine = onUnloadEngine,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("自动卸载引擎 (后台5分钟)")
                        Switch(checked = uiState.autoUnload, onCheckedChange = onSetAutoUnload)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ModelCard(
    config: OnDeviceModelConfig,
    state: OnDeviceModelState?,
    progress: DownloadProgress?,
    isEngineLoaded: Boolean,
    isLoading: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onLoadEngine: (BackendPreference) -> Unit,
    onUnloadEngine: () -> Unit,
) {
    val downloadState = state?.downloadState ?: DownloadState.NOT_DOWNLOADED
    val engineState = state?.engineState ?: EngineState.NOT_LOADED
    val capLabels = remember(config.capabilities) {
        config.capabilities.map { cap ->
            when (cap) {
                OnDeviceCapability.TEXT -> "文本"
                OnDeviceCapability.VISION -> "图像"
                OnDeviceCapability.AUDIO -> "音频"
                OnDeviceCapability.THINKING -> "推理"
                OnDeviceCapability.TOOL_CALL -> "工具调用"
            }
        }
    }
    val fileSizeStr = remember(config.fileSize) {
        val gb = config.fileSize / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(config.fileSize / (1024.0 * 1024.0))
    }
    val progressPercent by derivedStateOf {
        if (progress != null && progress.totalBytes > 0) {
            (progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat() * 100).toInt()
        } else 0
    }
    val speedStr = remember(progress) {
        if (progress != null && progress.speed > 0) {
            val mbps = progress.speed / (1024.0 * 1024.0)
            "%.1f MB/s".format(mbps)
        } else ""
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEngineLoaded)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(config.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(fileSizeStr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(capLabels.joinToString(" + "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("最低 ${config.minRamMb / 1024}GB RAM / 推荐 ${config.recommendedRamMb / 1024}GB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (downloadState) {
                DownloadState.NOT_DOWNLOADED -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("下载模型")
                    }
                }
                DownloadState.DOWNLOADING -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("$progressPercent%  $speedStr", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onPause) { Text("暂停") }
                    }
                }
                DownloadState.PAUSED -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("继续下载")
                    }
                }
                DownloadState.DOWNLOADED -> {
                    Spacer(Modifier.height(8.dp))
                    when (engineState) {
                        EngineState.NOT_LOADED -> {
                            EngineLoadRow(onLoadEngine = onLoadEngine, isLoading = isLoading)
                        }
                        EngineState.LOADING -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("引擎加载中...")
                            }
                        }
                        EngineState.LOADED -> {
                            Text("引擎已加载 (${state?.activeBackend ?: ""})", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onUnloadEngine) { Text("卸载引擎") }
                                OutlinedButton(
                                    onClick = onDelete,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) { Text("删除模型") }
                            }
                        }
                        EngineState.LOAD_FAILED -> {
                            Text("引擎加载失败", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            EngineLoadRow(onLoadEngine = onLoadEngine, isLoading = isLoading)
                        }
                        EngineState.UNLOADING -> {
                            Text("引擎卸载中...")
                        }
                    }
                }
                DownloadState.DOWNLOAD_FAILED -> {
                    Spacer(Modifier.height(8.dp))
                    Text("下载失败", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("重新下载")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineLoadRow(
    onLoadEngine: (BackendPreference) -> Unit,
    isLoading: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedBackend by remember { mutableStateOf(BackendPreference.AUTO) }
    val backendLabel = when (selectedBackend) {
        BackendPreference.AUTO -> "自动"
        BackendPreference.CPU -> "CPU"
        BackendPreference.GPU -> "GPU"
        BackendPreference.NPU -> "NPU"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(),
            ) {
                Text("后端: $backendLabel")
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("自动 (GPU→CPU)") }, onClick = { selectedBackend = BackendPreference.AUTO; expanded = false })
                DropdownMenuItem(text = { Text("CPU") }, onClick = { selectedBackend = BackendPreference.CPU; expanded = false })
                DropdownMenuItem(text = { Text("GPU") }, onClick = { selectedBackend = BackendPreference.GPU; expanded = false })
                DropdownMenuItem(text = { Text("NPU") }, onClick = { selectedBackend = BackendPreference.NPU; expanded = false })
            }
        }
        Button(
            onClick = { onLoadEngine(selectedBackend) },
            enabled = !isLoading,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text("加载引擎")
        }
    }
}

private data class DownloadItem(
    val modelId: String,
    val state: OnDeviceModelState,
    val progress: DownloadProgress?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadManagementSheet(
    downloads: List<DownloadItem>,
    availableModels: List<OnDeviceModelConfig>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "下载管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            if (downloads.isEmpty()) {
                Text("暂无下载任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                downloads.forEach { item ->
                    val config = availableModels.find { it.id == item.modelId }
                    val progressPercent = if (item.progress != null && item.progress.totalBytes > 0) {
                        (item.progress.downloadedBytes.toFloat() / item.progress.totalBytes.toFloat() * 100).toInt()
                    } else 0
                    val speedStr = if (item.progress != null && item.progress.speed > 0) {
                        val mbps = item.progress.speed / (1024.0 * 1024.0)
                        "%.1f MB/s".format(mbps)
                    } else ""

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(config?.name ?: item.modelId, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("$progressPercent%  $speedStr", style = MaterialTheme.typography.bodySmall)
                                if (item.state.downloadState == DownloadState.DOWNLOADING) {
                                    OutlinedButton(onClick = { onPause(item.modelId) }) { Text("暂停") }
                                } else if (item.state.downloadState == DownloadState.PAUSED) {
                                    Button(onClick = { onResume(item.modelId) }) { Text("继续") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
