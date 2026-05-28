package com.lin.hippyagent.ui.settings.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class CronJobUiItem(
    val id: String,
    val name: String,
    val query: String,
    val schedule: String,
    val agentId: String,
    val enabled: Boolean,
    val sessionId: String = "",
    val silentMode: Boolean = false
)

@Immutable
data class SessionUiItem(
    val id: String,
    val title: String
)

@Immutable
data class CronJobsUiState(
    val jobs: List<CronJobUiItem> = emptyList(),
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
    val editingJob: CronJobUiItem? = null,
    val sessions: List<SessionUiItem> = emptyList()
)

class CronJobsViewModel(
    private val cronJobManager: com.lin.hippyagent.core.cron.CronJobManager? = null,
    private val agentId: String = "",
    private val sessionStore: com.lin.hippyagent.core.agent.session.SessionStore? = null
) : ViewModel() {
    private val _state = MutableStateFlow(CronJobsUiState())
    val state: StateFlow<CronJobsUiState> = _state.asStateFlow()

    init {
        loadJobs()
        loadSessions()
    }

    fun loadJobs() {
        val jobs = cronJobManager?.getJobs()?.map { job ->
            CronJobUiItem(
                id = job.id,
                name = job.name,
                query = job.query,
                schedule = job.schedule,
                agentId = job.agentId,
                enabled = job.enabled,
                sessionId = job.sessionId,
                silentMode = job.silentMode
            )
        } ?: emptyList()
        _state.value = _state.value.copy(jobs = jobs, isLoading = false)
    }

    private fun loadSessions() {
        viewModelScope.launch {
            val sessions = sessionStore?.getSessionsForAgent(agentId)?.getOrNull()?.map {
                SessionUiItem(id = it.id, title = it.title)
            } ?: emptyList()
            _state.value = _state.value.copy(sessions = sessions)
        }
    }

    fun addJob(name: String, query: String, schedule: String, sessionId: String = "", silentMode: Boolean = false) {
        if (cronJobManager == null) return
        val job = com.lin.hippyagent.core.cron.CronJob(
            name = name,
            query = query,
            schedule = schedule,
            agentId = agentId,
            sessionId = sessionId,
            silentMode = silentMode
        )
        viewModelScope.launch {
            cronJobManager.createJob(job)
            loadJobs()
        }
    }

    fun deleteJob(id: String) {
        viewModelScope.launch {
            cronJobManager?.deleteJob(id)
            loadJobs()
        }
    }

    fun toggleJob(id: String, enabled: Boolean) {
        val job = cronJobManager?.getJob(id) ?: return
        viewModelScope.launch {
            cronJobManager.updateJob(job.copy(enabled = enabled))
            loadJobs()
        }
    }

    fun showAddDialog() {
        _state.value = _state.value.copy(showAddDialog = true, editingJob = null)
    }

    fun dismissAddDialog() {
        _state.value = _state.value.copy(showAddDialog = false, editingJob = null)
    }

    fun showEditDialog(job: CronJobUiItem) {
        _state.value = _state.value.copy(showAddDialog = true, editingJob = job)
    }

    fun editJob(id: String, name: String, query: String, schedule: String, sessionId: String = "", silentMode: Boolean = false) {
        if (cronJobManager == null) return
        val existing = cronJobManager.getJob(id) ?: return
        viewModelScope.launch {
            cronJobManager.updateJob(existing.copy(name = name, query = query, schedule = schedule, sessionId = sessionId, silentMode = silentMode))
            loadJobs()
            _state.value = _state.value.copy(showAddDialog = false, editingJob = null)
        }
    }
}

// ─────────────────────────────────────────────
// Cron 表达式构建器选项
// ─────────────────────────────────────────────

private data class CronOption(val label: String, val value: String)

private val MINUTE_OPTIONS = listOf(
    CronOption("每分钟", "*"),
    CronOption("每5分钟", "*/5"),
    CronOption("每10分钟", "*/10"),
    CronOption("每15分钟", "*/15"),
    CronOption("每30分钟", "*/30"),
    CronOption("整点(0分)", "0")
)

private val HOUR_OPTIONS = listOf(
    CronOption("每小时", "*"),
    CronOption("每2小时", "*/2"),
    CronOption("每4小时", "*/4"),
    CronOption("每6小时", "*/6"),
    CronOption("每12小时", "*/12"),
    CronOption("凌晨0点", "0"),
    CronOption("早上8点", "8"),
    CronOption("上午9点", "9"),
    CronOption("下午3点", "15"),
    CronOption("晚上9点", "21")
)

private val DOM_OPTIONS = listOf(
    CronOption("每天", "*"),
    CronOption("1号", "1"),
    CronOption("15号", "15"),
    CronOption("最后一天", "L")
)

private val MONTH_OPTIONS = listOf(
    CronOption("每月", "*"),
    CronOption("1月", "1"),
    CronOption("3月", "3"),
    CronOption("6月", "6"),
    CronOption("9月", "9"),
    CronOption("12月", "12")
)

private val DOW_OPTIONS = listOf(
    CronOption("每天", "*"),
    CronOption("工作日", "1-5"),
    CronOption("周末", "6,0"),
    CronOption("周一", "1"),
    CronOption("周三", "3"),
    CronOption("周五", "5"),
    CronOption("周六", "6"),
    CronOption("周日", "0")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronDropdown(
    label: String,
    options: List<CronOption>,
    selected: CronOption,
    onSelected: (CronOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 12.sp) },
            trailingIcon = {
                Icon(Icons.Default.KeyboardArrowDown, null,
                    modifier = Modifier.menuAnchor())
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(opt.label, fontSize = 13.sp)
                            Text(opt.value, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        onSelected(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronJobsScreen(
    viewModel: CronJobsViewModel,
    onBackClick: () -> Unit = {}
) {
    BackHandler(onBack = onBackClick)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var newQuery by remember { mutableStateOf("") }
    var selectedSessionId by remember { mutableStateOf("") }
    var silentMode by remember { mutableStateOf(false) }

    // Cron 各字段选中状态
    var selMinute by remember { mutableStateOf(MINUTE_OPTIONS[5]) }   // 0
    var selHour by remember { mutableStateOf(HOUR_OPTIONS[5]) }       // 0
    var selDom by remember { mutableStateOf(DOM_OPTIONS[0]) }         // *
    var selMonth by remember { mutableStateOf(MONTH_OPTIONS[0]) }     // *
    var selDow by remember { mutableStateOf(DOW_OPTIONS[0]) }         // *

    // 编辑模式时，从 editingJob 预填充字段
    LaunchedEffect(state.editingJob) {
        val editing = state.editingJob
        if (editing != null) {
            newName = editing.name
            newQuery = editing.query
            selectedSessionId = editing.sessionId
            silentMode = editing.silentMode
            val parts = editing.schedule.split(" ")
            if (parts.size >= 5) {
                selMinute = MINUTE_OPTIONS.find { it.value == parts[0] } ?: MINUTE_OPTIONS[5]
                selHour = HOUR_OPTIONS.find { it.value == parts[1] } ?: HOUR_OPTIONS[5]
                selDom = DOM_OPTIONS.find { it.value == parts[2] } ?: DOM_OPTIONS[0]
                selMonth = MONTH_OPTIONS.find { it.value == parts[3] } ?: MONTH_OPTIONS[0]
                selDow = DOW_OPTIONS.find { it.value == parts[4] } ?: DOW_OPTIONS[0]
            }
        } else {
            selectedSessionId = ""
            silentMode = false
        }
    }

    // 根据选择自动拼出 cron 表达式
    val builtSchedule = "${selMinute.value} ${selHour.value} ${selDom.value} ${selMonth.value} ${selDow.value}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定时任务") },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text("返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddDialog() }) {
                Icon(Icons.Default.Add, contentDescription = "添加定时任务")
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.jobs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无定时任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("点击 + 创建第一个定时任务", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(state.jobs, key = { it.id }) { job ->
                    CronJobCard(
                        job = job,
                        onToggle = { enabled -> viewModel.toggleJob(job.id, enabled) },
                        onDelete = { viewModel.deleteJob(job.id) },
                        onClick = { viewModel.showEditDialog(job) }
                    )
                }
            }
        }
    }

    // 添加/编辑对话框
    if (state.showAddDialog) {
        val isEditing = state.editingJob != null
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissAddDialog() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isEditing) "编辑定时任务" else "新建定时任务",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("任务名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newQuery,
                        onValueChange = { newQuery = it },
                        label = { Text("执行内容") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    // 选择会话
                    if (state.sessions.isNotEmpty()) {
                        var sessionExpanded by remember { mutableStateOf(false) }
                        val selectedSession = state.sessions.find { it.id == selectedSessionId }
                        ExposedDropdownMenuBox(
                            expanded = sessionExpanded,
                            onExpandedChange = { sessionExpanded = !sessionExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedSession?.title ?: "新建会话",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("选择会话", fontSize = 12.sp) },
                                trailingIcon = {
                                    Icon(Icons.Default.KeyboardArrowDown, null,
                                        modifier = Modifier.menuAnchor())
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = sessionExpanded,
                                onDismissRequest = { sessionExpanded = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                DropdownMenuItem(
                                    text = { Text("新建会话", fontSize = 13.sp) },
                                    onClick = {
                                        selectedSessionId = ""
                                        sessionExpanded = false
                                    }
                                )
                                state.sessions.forEach { session ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                session.title.ifBlank { session.id.take(8) },
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            selectedSessionId = session.id
                                            sessionExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 后台静默执行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("后台静默执行", fontSize = 14.sp)
                        Switch(
                            checked = silentMode,
                            onCheckedChange = { silentMode = it },
                            modifier = Modifier.height(24.dp)
                        )
                    }

                    // Cron 表达式多项选择
                    Text("执行频率", fontWeight = FontWeight.Medium, fontSize = 14.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CronDropdown("分钟", MINUTE_OPTIONS, selMinute, { selMinute = it }, Modifier.weight(1f))
                        CronDropdown("小时", HOUR_OPTIONS, selHour, { selHour = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CronDropdown("日期", DOM_OPTIONS, selDom, { selDom = it }, Modifier.weight(1f))
                        CronDropdown("月份", MONTH_OPTIONS, selMonth, { selMonth = it }, Modifier.weight(1f))
                    }
                    CronDropdown("星期", DOW_OPTIONS, selDow, { selDow = it })

                    // 预览生成的 cron 表达式
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Cron: $builtSchedule",
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { viewModel.dismissAddDialog() }) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank() && newQuery.isNotBlank()) {
                                if (isEditing) {
                                    state.editingJob?.let { job ->
                                        viewModel.editJob(job.id, newName, newQuery, builtSchedule, selectedSessionId, silentMode)
                                    }
                                } else {
                                    viewModel.addJob(newName, newQuery, builtSchedule, selectedSessionId, silentMode)
                                }
                                newName = ""
                                newQuery = ""
                                selectedSessionId = ""
                                silentMode = false
                                selMinute = MINUTE_OPTIONS[5]
                                selHour = HOUR_OPTIONS[5]
                                selDom = DOM_OPTIONS[0]
                                selMonth = MONTH_OPTIONS[0]
                                selDow = DOW_OPTIONS[0]
                                viewModel.dismissAddDialog()
                            }
                        },
                        enabled = newName.isNotBlank() && newQuery.isNotBlank()
                    ) {
                        Text(if (isEditing) "保存" else "创建")
                    }
                }
            }
        }
    }

@Composable
private fun CronJobCard(
    job: CronJobUiItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (job.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Text(
                        text = job.schedule,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = job.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.height(24.dp)
                    )
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Text(
                text = job.query,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (job.silentMode) {
                Text(
                    text = "静默执行",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}


