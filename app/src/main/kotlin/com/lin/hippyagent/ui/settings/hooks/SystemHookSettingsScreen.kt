package com.lin.hippyagent.ui.settings.hooks

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.hooks.system.SystemEventType
import com.lin.hippyagent.core.hooks.system.SystemHookManager
import com.lin.hippyagent.ui.components.HippyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class HookUiState(
    val hookStatuses: Map<SystemEventType, HookStatus> = emptyMap(),
    val silentEnabled: Boolean = false,
    val silentStartHour: Int = 23,
    val silentEndHour: Int = 7
)

data class HookStatus(
    val enabled: Boolean = false,
    val hasPermission: Boolean = false,
    val description: String = ""
)

class SystemHookViewModel(
    private val hookManager: SystemHookManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(HookUiState())
    val uiState: StateFlow<HookUiState> = _uiState

    fun refresh(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val statuses = mutableMapOf<SystemEventType, HookStatus>()
                val hooks = hookManager.getRegisteredHooks()
                for (hook in hooks) {
                    val hasPermission = hook.isEnabled(context)
                    for (eventType in hook.eventTypes) {
                        statuses[eventType] = HookStatus(
                            enabled = hasPermission,
                            hasPermission = hasPermission,
                            description = hook.description
                        )
                    }
                }
                val (silentEnabled, startMin, endMin) = hookManager.eventFilter.getSilentConfig()
                _uiState.value = HookUiState(
                    hookStatuses = statuses,
                    silentEnabled = silentEnabled,
                    silentStartHour = startMin / 60,
                    silentEndHour = endMin / 60
                )
            }
        }
    }

    fun setSilentEnabled(enabled: Boolean) {
        val state = _uiState.value
        hookManager.eventFilter.setSilentHours(
            enabled,
            state.silentStartHour * 60,
            state.silentEndHour * 60
        )
        _uiState.value = state.copy(silentEnabled = enabled)
    }

    fun setSilentStartHour(hour: Int) {
        val state = _uiState.value
        hookManager.eventFilter.setSilentHours(state.silentEnabled, hour * 60, state.silentEndHour * 60)
        _uiState.value = state.copy(silentStartHour = hour)
    }

    fun setSilentEndHour(hour: Int) {
        val state = _uiState.value
        hookManager.eventFilter.setSilentHours(state.silentEnabled, state.silentStartHour * 60, hour * 60)
        _uiState.value = state.copy(silentEndHour = hour)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemHookSettingsScreen(
    viewModel: SystemHookViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh(context)
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = "事件监听",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("系统事件监听", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Agent 可主动响应系统事件（短信、来电、电量变化等），实现主动感知能力。开启后需要授予对应权限。",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val displayTypes = listOf(
                SystemEventType.SMS_RECEIVED,
                SystemEventType.INCOMING_CALL,
                SystemEventType.NOTIFICATION_POSTED,
                SystemEventType.BATTERY_LOW,
                SystemEventType.SCREEN_ON,
                SystemEventType.APP_INSTALLED,
                SystemEventType.CLIPBOARD_CHANGED,
                SystemEventType.BOOT_COMPLETED
            )

            items(displayTypes, key = { it.name }) { type ->
                val status = uiState.hookStatuses[type]
                val isEnabled = status?.enabled == true
                val hasPermission = status?.hasPermission == true

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(type.description, fontSize = 14.sp)
                            if (!hasPermission) {
                                Text(
                                    "缺少权限",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (isEnabled) {
                                Text(
                                    "已启用",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    "未启用",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { },
                            enabled = hasPermission
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("静默时间", fontSize = 14.sp)
                                Text(
                                    "静默期间不触发事件（来电除外）",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.silentEnabled,
                                onCheckedChange = { viewModel.setSilentEnabled(it) }
                            )
                        }
                        if (uiState.silentEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = uiState.silentStartHour.toString(),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { h ->
                                            if (h in 0..23) viewModel.setSilentStartHour(h)
                                        }
                                    },
                                    label = { Text("开始时") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = uiState.silentEndHour.toString(),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { h ->
                                            if (h in 0..23) viewModel.setSilentEndHour(h)
                                        }
                                    },
                                    label = { Text("结束时") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}
