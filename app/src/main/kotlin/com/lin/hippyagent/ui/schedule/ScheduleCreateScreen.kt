package com.lin.hippyagent.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.core.scheduler.ParseMethod
import com.lin.hippyagent.core.scheduler.ScheduleCreateUiState
import com.lin.hippyagent.core.scheduler.ScheduleCreateViewModel
import com.lin.hippyagent.core.scheduler.ScheduleParsePhase
import com.lin.hippyagent.core.scheduler.ScheduleParseResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleCreateScreen(
    viewModel: ScheduleCreateViewModel,
    onBackClick: () -> Unit = {},
    onSaved: () -> Unit = {}
) {
    BackHandler(onBack = onBackClick)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedule_create_title)) },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text(stringResource(R.string.nav_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NaturalLanguageInputSection(
                nlText = state.nlText,
                onTextChange = viewModel::onNlTextChange,
                phase = state.phase
            )

            TaskNameSection(
                taskName = state.taskName,
                onTaskNameChange = viewModel::onTaskNameChange
            )

            SilentModeRow(
                silentMode = state.silentMode,
                onChange = viewModel::onSilentModeChange
            )

            PreviewSection(
                state = state,
                onSelectCandidate = viewModel::selectCandidate
            )

            ErrorSection(
                errorMessage = state.errorMessage,
                manualCron = state.manualCron,
                manualMode = state.manualMode,
                onManualCronChange = viewModel::onManualCronChange,
                onToggleManual = viewModel::toggleManualMode
            )

            Spacer(Modifier.height(8.dp))

            SaveButton(
                enabled = state.canSave,
                saving = state.saving,
                onClick = viewModel::save
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NaturalLanguageInputSection(
    nlText: String,
    onTextChange: (String) -> Unit,
    phase: ScheduleParsePhase
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.schedule_nl_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            if (phase == ScheduleParsePhase.PARSING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        OutlinedTextField(
            value = nlText,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.schedule_nl_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            minLines = 4,
            maxLines = 8,
            singleLine = false,
            leadingIcon = {
                Icon(Icons.Default.Schedule, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            text = stringResource(R.string.schedule_nl_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TaskNameSection(
    taskName: String,
    onTaskNameChange: (String) -> Unit
) {
    OutlinedTextField(
        value = taskName,
        onValueChange = onTaskNameChange,
        label = { Text(stringResource(R.string.schedule_task_name_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun SilentModeRow(
    silentMode: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.schedule_silent_label),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.schedule_silent_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = silentMode,
            onCheckedChange = onChange
        )
    }
}

@Composable
private fun PreviewSection(
    state: ScheduleCreateUiState,
    onSelectCandidate: (Int) -> Unit
) {
    val result = state.parseResult
    val isManualActive = state.manualMode

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.schedule_preview_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (result != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text(parseMethodLabel(result.parseMethod), fontSize = 11.sp) }
                    )
                }
            }

            if (result == null) {
                Text(
                    text = stringResource(R.string.schedule_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (result.success && !isManualActive) {
                val effective = if (result.ambiguityCandidates.isNotEmpty()) {
                    val idx = state.selectedCandidateIndex.coerceIn(0, result.ambiguityCandidates.size - 1)
                    result.ambiguityCandidates[idx]
                } else {
                    result
                }
                PreviewLines(result = effective)
                if (result.ambiguityCandidates.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.schedule_ambiguity_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    AmbiguityCandidates(
                        candidates = result.ambiguityCandidates,
                        selectedIndex = state.selectedCandidateIndex,
                        onSelect = onSelectCandidate
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.schedule_preview_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PreviewLines(result: ScheduleParseResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        result.cron?.takeIf { it.isNotBlank() }?.let { cron ->
            PreviewRow(
                label = stringResource(R.string.schedule_preview_cron),
                value = cron,
                monospace = true
            )
        }
        result.humanReadable?.takeIf { it.isNotBlank() }?.let { human ->
            PreviewRow(
                label = stringResource(R.string.schedule_preview_human),
                value = human
            )
        }
        result.nextFireTime?.let { ms ->
            PreviewRow(
                label = stringResource(R.string.schedule_preview_next),
                value = formatTimestamp(ms)
            )
        }
        if (result.isOneShot) {
            Text(
                text = stringResource(R.string.schedule_oneshot_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun PreviewRow(
    label: String,
    value: String,
    monospace: Boolean = false
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (monospace) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun AmbiguityCandidates(
    candidates: List<ScheduleParseResult>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        candidates.forEachIndexed { index, candidate ->
            val isSelected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(index) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelect(index) }
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = candidate.humanReadable.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        candidate.nextFireTime?.let { ms ->
                            Text(
                                text = formatTimestamp(ms),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorSection(
    errorMessage: String?,
    manualCron: String,
    manualMode: Boolean,
    onManualCronChange: (String) -> Unit,
    onToggleManual: (Boolean) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.schedule_manual_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
                AnimatedVisibility(visible = expanded || manualMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.schedule_manual_enable),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Switch(
                                checked = manualMode,
                                onCheckedChange = onToggleManual
                            )
                        }
                        OutlinedTextField(
                            value = manualCron,
                            onValueChange = onManualCronChange,
                            label = { Text(stringResource(R.string.schedule_manual_cron_label)) },
                            placeholder = { Text("0 15 * * *") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Text(
                            text = stringResource(R.string.schedule_manual_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveButton(
    enabled: Boolean,
    saving: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (saving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = stringResource(R.string.schedule_save),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

private fun parseMethodLabel(method: ParseMethod): String = when (method) {
    ParseMethod.LLM -> "LLM"
    ParseMethod.RULE -> "规则兜底"
    ParseMethod.FALLBACK -> "手动"
}

private fun formatTimestamp(epochMs: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    return fmt.format(Date(epochMs))
}
