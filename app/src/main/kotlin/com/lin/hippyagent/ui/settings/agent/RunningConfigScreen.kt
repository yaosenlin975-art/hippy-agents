package com.lin.hippyagent.ui.settings.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.core.agent.config.RunningConfig
import com.lin.hippyagent.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RunningConfigScreen(
    viewModel: RunningConfigViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 去除「上下文压缩」Tab，保留四个页面
    val tabs = listOf(stringResource(R.string.running_config_tab_react), stringResource(R.string.running_config_tab_retry), stringResource(R.string.running_config_tab_concurrent), stringResource(R.string.running_config_tab_memory))
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.running_config_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.nav_back)) }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Tab 指示器，点击跳转到对应页
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }

            // 左右滑动切换页面
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (page) {
                        0 -> ReactConfigSection(uiState.runningConfig, viewModel::updateConfig)
                        1 -> LlmRetrySection(uiState.runningConfig, viewModel::updateConfig)
                        2 -> LlmConcurrencySection(uiState.runningConfig, viewModel::updateConfig)
                        3 -> LongTermMemorySection(uiState.runningConfig, viewModel::updateConfig)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.resetConfig() },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.running_config_reset_default)) }

                        Button(
                            onClick = { viewModel.saveConfig() },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isSaving
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }

            if (uiState.saveSuccess == true) {
                LaunchedEffect(uiState.saveSuccess) {
                    snackbarHostState.showSnackbar(context.getString(R.string.running_config_saved))
                }
            }
        }
    }
}

@Composable
private fun ReactConfigSection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.running_config_react_agent), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            NumberField(stringResource(R.string.running_config_max_iters), config.maxIters) { onUpdate(config.copy(maxIters = it)) }
            SwitchField(stringResource(R.string.running_config_auto_continue), config.autoContinueOnTextOnly) { onUpdate(config.copy(autoContinueOnTextOnly = it)) }
            NumberField(stringResource(R.string.running_config_max_input_length), config.maxInputLength) { onUpdate(config.copy(maxInputLength = it)) }
        }
    }
}

@Composable
private fun LlmRetrySection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.running_config_llm_auto_retry), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            SwitchField(stringResource(R.string.running_config_enable_retry), config.llmRetryEnabled) { onUpdate(config.copy(llmRetryEnabled = it)) }
            if (config.llmRetryEnabled) {
                NumberField(stringResource(R.string.running_config_max_retries), config.llmRetryMaxRetries) { onUpdate(config.copy(llmRetryMaxRetries = it)) }
                FloatField(stringResource(R.string.running_config_backoff_base), config.llmRetryBackoffBase) { onUpdate(config.copy(llmRetryBackoffBase = it)) }
                FloatField(stringResource(R.string.running_config_backoff_cap), config.llmRetryBackoffCap) { onUpdate(config.copy(llmRetryBackoffCap = it)) }
            }
        }
    }
}

@Composable
private fun LlmConcurrencySection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.running_config_llm_concurrent), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            NumberField(stringResource(R.string.running_config_max_concurrent), config.llmMaxConcurrent) { onUpdate(config.copy(llmMaxConcurrent = it)) }
            NumberField(stringResource(R.string.running_config_max_qpm), config.llmMaxQpm) { onUpdate(config.copy(llmMaxQpm = it)) }
            FloatField(stringResource(R.string.running_config_rate_limit_pause), config.llmRateLimitPause) { onUpdate(config.copy(llmRateLimitPause = it)) }
            FloatField(stringResource(R.string.running_config_rate_limit_jitter), config.llmRateLimitJitter) { onUpdate(config.copy(llmRateLimitJitter = it)) }
            FloatField(stringResource(R.string.running_config_acquire_timeout), config.llmAcquireTimeout) { onUpdate(config.copy(llmAcquireTimeout = it)) }
        }
    }
}

@Composable
private fun ContextCompressionSection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.running_config_context_compression), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                value = config.contextManagerBackend,
                onValueChange = { onUpdate(config.copy(contextManagerBackend = it)) },
                label = { Text(stringResource(R.string.running_config_context_backend)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.memoryManagerBackend,
                onValueChange = { onUpdate(config.copy(memoryManagerBackend = it)) },
                label = { Text(stringResource(R.string.running_config_memory_backend)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LongTermMemorySection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.running_config_long_term_memory_label), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            SwitchField(stringResource(R.string.running_config_memory_on_compact), config.remeLightMemoryConfig.summarizeWhenCompact) { v ->
                onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(summarizeWhenCompact = v)))
            }
            SwitchField(stringResource(R.string.running_config_auto_memory_search), config.remeLightMemoryConfig.autoMemorySearchConfig.enabled) { v ->
                onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(
                    autoMemorySearchConfig = config.remeLightMemoryConfig.autoMemorySearchConfig.copy(enabled = v)
                )))
            }
            if (config.remeLightMemoryConfig.autoMemorySearchConfig.enabled) {
                NumberField(stringResource(R.string.running_config_auto_search_max_results), config.remeLightMemoryConfig.autoMemorySearchConfig.maxResults) { v ->
                    onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(
                        autoMemorySearchConfig = config.remeLightMemoryConfig.autoMemorySearchConfig.copy(maxResults = v)
                    )))
                }
                SliderField(
                    label = stringResource(R.string.running_config_auto_search_min_score),
                    value = config.remeLightMemoryConfig.autoMemorySearchConfig.minScore,
                    valueRange = 0f..1f
                ) { v ->
                    onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(
                        autoMemorySearchConfig = config.remeLightMemoryConfig.autoMemorySearchConfig.copy(minScore = v)
                    )))
                }
            }
            SwitchField(stringResource(R.string.running_config_rebuild_index), config.remeLightMemoryConfig.rebuildMemoryIndexOnStart) { v ->
                onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(rebuildMemoryIndexOnStart = v)))
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let { v -> onChange(v) }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun FloatField(label: String, value: Float, onChange: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(String.format("%.1f", value)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toFloatOrNull()?.let { v -> onChange(v) }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun SwitchField(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderField(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text(String.format("%.2f", value), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

