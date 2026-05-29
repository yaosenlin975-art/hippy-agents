package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.session.AppDatabase
import com.lin.hippyagent.core.agent.session.DreamHistoryEntity
import com.lin.hippyagent.core.memory.DreamMemoryManager
import com.lin.hippyagent.core.skill.curator.CuratorEngine
import com.lin.hippyagent.core.skill.curator.CuratorSkillManifest
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DreamViewModel(
    private val dreamManager: DreamMemoryManager,
    private val database: AppDatabase
) : ViewModel() {
    private val _history = MutableStateFlow<List<DreamHistoryEntity>>(emptyList())
    val history: StateFlow<List<DreamHistoryEntity>> = _history

    private val _triggering = MutableStateFlow(false)
    val triggering: StateFlow<Boolean> = _triggering

    private val _curatorSkills = MutableStateFlow<List<CuratorSkillManifest>>(emptyList())
    val curatorSkills: StateFlow<List<CuratorSkillManifest>> = _curatorSkills

    private val _curatorStats = MutableStateFlow<Triple<Int, Int, Int>>(Triple(0, 0, 0))
    val curatorStats: StateFlow<Triple<Int, Int, Int>> = _curatorStats

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _history.value = database.dreamHistoryDao().getRecent(20)
        }
    }

    fun loadCuratorData(curatorEngine: CuratorEngine) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _curatorSkills.value = curatorEngine.getAutoSkills()
                _curatorStats.value = curatorEngine.getAutoSkillStats()
            }
        }
    }

    fun triggerNow() {
        viewModelScope.launch {
            _triggering.value = true
            dreamManager.triggerDream()
            loadHistory()
            _triggering.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DreamScreen(
    onBackClick: () -> Unit,
    viewModel: DreamViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var dreamEnabled by remember { mutableStateOf(false) }
    var dreamIntervalHours by remember { mutableStateOf("24") }
    var dreamOnCharge by remember { mutableStateOf(true) }
    var dreamOnWifi by remember { mutableStateOf(true) }
    var dreamOnIdle by remember { mutableStateOf(true) }
    var retentionDays by remember { mutableStateOf("30") }

    val history by viewModel.history.collectAsStateWithLifecycle()
    val triggering by viewModel.triggering.collectAsStateWithLifecycle()
    val curatorSkills by viewModel.curatorSkills.collectAsStateWithLifecycle()
    val curatorStats by viewModel.curatorStats.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val curatorEngine = remember {
        try {
            org.koin.java.KoinJavaComponent.getKoin().get<CuratorEngine>()
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(curatorEngine) {
        curatorEngine?.let { viewModel.loadCuratorData(it) }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.dream_title),
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.dream_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dream_desc),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dream_enable), fontSize = 14.sp)
                            Text(stringResource(R.string.dream_enable_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = dreamEnabled,
                            onCheckedChange = { enabled ->
                                dreamEnabled = enabled
                            }
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.dream_interval_hours), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = dreamIntervalHours,
                            onValueChange = { dreamIntervalHours = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = dreamEnabled
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dream_charge_only), fontSize = 14.sp)
                            }
                            Switch(checked = dreamOnCharge, onCheckedChange = { dreamOnCharge = it }, enabled = dreamEnabled)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dream_wifi_required), fontSize = 14.sp)
                            }
                            Switch(checked = dreamOnWifi, onCheckedChange = { dreamOnWifi = it }, enabled = dreamEnabled)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dream_idle_only), fontSize = 14.sp)
                            }
                            Switch(checked = dreamOnIdle, onCheckedChange = { dreamOnIdle = it }, enabled = dreamEnabled)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.dream_retention_days), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = retentionDays,
                            onValueChange = { retentionDays = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = dreamEnabled
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.triggerNow() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !triggering
                ) {
                    if (triggering) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(if (triggering) stringResource(R.string.dream_executing) else stringResource(R.string.dream_trigger_now))
                }
            }

            if (history.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.dream_history), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                items(history, key = { it.triggeredAt }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    formatTimestamp(entry.triggeredAt),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    entry.status,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (entry.status == "completed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            entry.message?.let {
                                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            entry.elapsedMs?.let { ms ->
                                Text(stringResource(R.string.dream_elapsed_ms, ms), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.dream_curator_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.dream_auto_skill_mgmt), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dream_curator_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(stringResource(R.string.dream_stat_extracted), curatorStats.first.toString())
                            StatItem(stringResource(R.string.dream_stat_active), curatorStats.second.toString())
                            StatItem(stringResource(R.string.dream_stat_archived), curatorStats.third.toString())
                        }
                    }
                }
            }

            if (curatorSkills.isNotEmpty()) {
                items(curatorSkills, key = { it.id }) { skill ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    skill.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stringResource(R.string.dream_skill_usage_count, skill.usageCount),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (skill.description.isNotBlank()) {
                                Text(
                                    skill.description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            if (skill.confidence > 0f) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.memory_confidence), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    LinearProgressIndicator(
                                        progress = { skill.confidence },
                                        modifier = Modifier.weight(1f).height(4.dp),
                                    )
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text(
                                        "${(skill.confidence * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.dream_no_auto_skills),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTimestamp(ts: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}
