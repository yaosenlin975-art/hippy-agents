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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RunningConfigScreen(
    viewModel: RunningConfigViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 去除「上下文压缩」Tab，保留四个页面
    val tabs = listOf("ReAct", "LLM重试", "并发限流", "长期记忆")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行配置") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
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
                        ) { Text("重置默认") }

                        Button(
                            onClick = { viewModel.saveConfig() },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isSaving
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("保存")
                            }
                        }
                    }
                }
            }

            if (uiState.saveSuccess == true) {
                LaunchedEffect(uiState.saveSuccess) {
                    snackbarHostState.showSnackbar("配置已保存")
                }
            }
        }
    }
}

@Composable
private fun ReactConfigSection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ReAct 智能体", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            NumberField("最大迭代次数", config.maxIters) { onUpdate(config.copy(maxIters = it)) }
            SwitchField("文本响应自动继续", config.autoContinueOnTextOnly) { onUpdate(config.copy(autoContinueOnTextOnly = it)) }
            NumberField("最大输入长度", config.maxInputLength) { onUpdate(config.copy(maxInputLength = it)) }
        }
    }
}

@Composable
private fun LlmRetrySection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LLM 自动重试", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            SwitchField("启用重试", config.llmRetryEnabled) { onUpdate(config.copy(llmRetryEnabled = it)) }
            if (config.llmRetryEnabled) {
                NumberField("最大重试次数", config.llmRetryMaxRetries) { onUpdate(config.copy(llmRetryMaxRetries = it)) }
                FloatField("退避基数(秒)", config.llmRetryBackoffBase) { onUpdate(config.copy(llmRetryBackoffBase = it)) }
                FloatField("退避上限(秒)", config.llmRetryBackoffCap) { onUpdate(config.copy(llmRetryBackoffCap = it)) }
            }
        }
    }
}

@Composable
private fun LlmConcurrencySection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("LLM 并发限流", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            NumberField("最大并发数", config.llmMaxConcurrent) { onUpdate(config.copy(llmMaxConcurrent = it)) }
            NumberField("最大QPM", config.llmMaxQpm) { onUpdate(config.copy(llmMaxQpm = it)) }
            FloatField("限流暂停(秒)", config.llmRateLimitPause) { onUpdate(config.copy(llmRateLimitPause = it)) }
            FloatField("限流抖动(秒)", config.llmRateLimitJitter) { onUpdate(config.copy(llmRateLimitJitter = it)) }
            FloatField("获取超时(秒)", config.llmAcquireTimeout) { onUpdate(config.copy(llmAcquireTimeout = it)) }
        }
    }
}

@Composable
private fun ContextCompressionSection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("上下文压缩", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                value = config.contextManagerBackend,
                onValueChange = { onUpdate(config.copy(contextManagerBackend = it)) },
                label = { Text("上下文管理后端") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.memoryManagerBackend,
                onValueChange = { onUpdate(config.copy(memoryManagerBackend = it)) },
                label = { Text("记忆管理后端") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LongTermMemorySection(config: RunningConfig, onUpdate: (RunningConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("长期记忆", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            SwitchField("压缩时记忆", config.remeLightMemoryConfig.summarizeWhenCompact) { v ->
                onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(summarizeWhenCompact = v)))
            }
            SwitchField("自动记忆搜索", config.remeLightMemoryConfig.autoMemorySearchConfig.enabled) { v ->
                onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(
                    autoMemorySearchConfig = config.remeLightMemoryConfig.autoMemorySearchConfig.copy(enabled = v)
                )))
            }
            if (config.remeLightMemoryConfig.autoMemorySearchConfig.enabled) {
                NumberField("自动搜索最大结果数", config.remeLightMemoryConfig.autoMemorySearchConfig.maxResults) { v ->
                    onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(
                        autoMemorySearchConfig = config.remeLightMemoryConfig.autoMemorySearchConfig.copy(maxResults = v)
                    )))
                }
                SliderField(
                    label = "自动搜索最低相关性分数",
                    value = config.remeLightMemoryConfig.autoMemorySearchConfig.minScore,
                    valueRange = 0f..1f
                ) { v ->
                    onUpdate(config.copy(remeLightMemoryConfig = config.remeLightMemoryConfig.copy(
                        autoMemorySearchConfig = config.remeLightMemoryConfig.autoMemorySearchConfig.copy(minScore = v)
                    )))
                }
            }
            SwitchField("启动时重建索引", config.remeLightMemoryConfig.rebuildMemoryIndexOnStart) { v ->
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

