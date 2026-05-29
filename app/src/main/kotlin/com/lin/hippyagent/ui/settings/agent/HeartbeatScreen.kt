package com.lin.hippyagent.ui.settings.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartbeatScreen(
    viewModel: HeartbeatViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.heartbeat_config_saved),
                duration = SnackbarDuration.Short
            )
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.heartbeat),
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { viewModel.resetConfig() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.running_config_reset_default), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    Text(stringResource(R.string.heartbeat_enable_section), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.heartbeat_enable_label), fontSize = 14.sp)
                            Switch(
                                checked = uiState.heartbeatConfig.enabled,
                                onCheckedChange = viewModel::updateEnabled
                            )
                        }
                    }
                }

                item {
                    Text(stringResource(R.string.heartbeat_interval), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = uiState.intervalValue.toString(),
                                onValueChange = {
                                    it.toIntOrNull()?.let { v -> viewModel.updateIntervalValue(v) }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = uiState.intervalValue < 1,
                                supportingText = if (uiState.intervalValue < 1) {
                                    { Text(stringResource(R.string.heartbeat_interval_zero_error), color = MaterialTheme.colorScheme.error) }
                                } else null
                            )
                            Row(modifier = Modifier.padding(start = 8.dp)) {
                                listOf("m", "h").forEach { unit ->
                                    TextButton(
                                        onClick = { viewModel.updateIntervalUnit(unit) },
                                        enabled = uiState.intervalUnit != unit
                                    ) {
                                        Text(
                                            text = unit,
                                            fontWeight = if (uiState.intervalUnit == unit) FontWeight.Bold else FontWeight.Normal,
                                            color = if (uiState.intervalUnit == unit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(stringResource(R.string.heartbeat_reply_target), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            listOf("main" to stringResource(R.string.heartbeat_target_main), "last" to stringResource(R.string.heartbeat_target_last)).forEach { (target, label) ->
                                val selected = uiState.heartbeatConfig.target == target
                                androidx.compose.material3.Card(
                                    modifier = Modifier.weight(1f).padding(0.dp),
                                    colors = androidx.compose.material3.CardDefaults.cardColors(
                                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    TextButton(onClick = { viewModel.updateTarget(target) }) {
                                        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(stringResource(R.string.heartbeat_active_hours), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    val activeHoursEnabled = uiState.heartbeatConfig.activeHours != null
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(stringResource(R.string.heartbeat_enable_active_hours), fontSize = 14.sp)
                                Text(stringResource(R.string.heartbeat_active_hours_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = activeHoursEnabled,
                                onCheckedChange = viewModel::updateActiveHoursEnabled
                            )
                        }
                    }
                }

                if (uiState.heartbeatConfig.activeHours != null) {
                    item {
                        androidx.compose.material3.Card(
                            modifier = Modifier.padding(top = 4.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.heartbeat_start_time), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = uiState.heartbeatConfig.activeHours?.start ?: "08:00",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    item {
                        androidx.compose.material3.Card(
                            modifier = Modifier.padding(top = 4.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.heartbeat_end_time), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = uiState.heartbeatConfig.activeHours?.end ?: "22:00",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                item { androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 32.dp)) }
            }
        }
    }
}
