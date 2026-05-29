package com.lin.hippyagent.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R
import com.lin.hippyagent.core.insights.AgentSessionCount
import com.lin.hippyagent.core.insights.InsightsReport
import com.lin.hippyagent.core.insights.ToolUsage
import com.lin.hippyagent.core.model.ModelUsageSummary
import com.lin.hippyagent.core.model.TokenUsageManager
import com.lin.hippyagent.core.model.TokenUsageSummary
import com.lin.hippyagent.ui.components.HippyTopBar
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onBackClick: () -> Unit,
    refreshTrigger: Boolean = true,
    modifier: Modifier = Modifier
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val selectedDays by viewModel.selectedDays.collectAsStateWithLifecycle()

    val tokenUsageManager = koinInject<TokenUsageManager>()

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger) viewModel.refresh()
    }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.insights_usage_analysis),
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.insights_refresh))
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        if (loading && report == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90).forEach { days ->
                        FilterChip(
                            selected = selectedDays == days,
                            onClick = { viewModel.setDays(days) },
                            label = { Text(stringResource(R.string.insights_days, days)) }
                        )
                    }
                }
            }

            report?.let { r ->
                item { OverviewCard(r) }
                item { AgentSessionCountCard(r.agentSessionCounts) }
                item { TokenUsageDetailCard(usageManager = tokenUsageManager, dailyTokenUsage = r.dailyTokenUsage) }
                item { ToolBreakdownCard(r.toolBreakdown) }
                item { SkillsBreakdownCard(r.skillBreakdown) }
                item { ActivityCard(r) }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AgentSessionCountCard(counts: List<AgentSessionCount>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.insights_agent_chat_count), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (counts.isEmpty()) {
                Text(stringResource(R.string.insights_no_data), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val maxCount = counts.maxOf { it.sessionCount }.coerceAtLeast(1)
                counts.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.agentName, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.insights_times, item.sessionCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(report: InsightsReport) {
    val o = report.overview
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.insights_overview), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(stringResource(R.string.insights_session_count), "${o.totalSessions}")
                StatItem(stringResource(R.string.insights_input_token), formatTokens(o.totalInputTokens))
                StatItem(stringResource(R.string.insights_output_token), formatTokens(o.totalOutputTokens))
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(stringResource(R.string.insights_completed), "${o.completedSessions}")
                StatItem(stringResource(R.string.insights_failed), "${o.failedSessions}")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SkillsBreakdownCard(skills: List<ToolUsage>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.insights_skill_ranking), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (skills.isEmpty()) {
                Text(stringResource(R.string.insights_no_data), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else skills.take(10).forEach { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.toolName, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.insights_times, s.callCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ToolBreakdownCard(tools: List<ToolUsage>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.insights_tool_ranking), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            tools.take(10).forEach { t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(t.toolName, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text(stringResource(R.string.insights_call_times, t.callCount), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(report: InsightsReport) {
    val a = report.activityPatterns
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.insights_activity_pattern), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.insights_peak_hours), fontSize = 13.sp)
                Text("${a.peakHour}:00", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.insights_peak_day), fontSize = 13.sp)
                Text(weekdayName(a.peakWeekday), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TokenTrendChart(dailyTokens: List<com.lin.hippyagent.core.insights.DailyTokenUsage>) {
    if (dailyTokens.isEmpty()) return
    val maxTokens = dailyTokens.maxOf { it.totalTokens }.coerceAtLeast(1)
    val labelInterval = (dailyTokens.size / 6).coerceAtLeast(1)
    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.secondary

    Text(stringResource(R.string.insights_token_trend), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawRect(inputColor)
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.insights_input), fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawRect(outputColor)
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.insights_output), fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    val barWidth = 6.dp
    val barGap = 2.dp
    val chartHeight = 120.dp
    Canvas(
        modifier = Modifier.fillMaxWidth().height(chartHeight)
    ) {
        val totalBars = dailyTokens.size
        val availableWidth = size.width
        val stepWidth = availableWidth / totalBars.coerceAtLeast(1)
        val ch = size.height

        dailyTokens.forEachIndexed { index, daily ->
            val x = index * stepWidth + (stepWidth - barWidth.toPx() * 2 - barGap.toPx()) / 2
            val inputH = (daily.inputTokens.toFloat() / maxTokens * ch).coerceAtMost(ch)
            val outputH = (daily.outputTokens.toFloat() / maxTokens * ch).coerceAtMost(ch)

            drawRect(
                color = inputColor,
                topLeft = Offset(x, ch - inputH),
                size = Size(barWidth.toPx(), inputH)
            )
            drawRect(
                color = outputColor.copy(alpha = 0.6f),
                topLeft = Offset(x + barWidth.toPx(), ch - outputH),
                size = Size(barWidth.toPx(), outputH)
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        dailyTokens.forEachIndexed { index, daily ->
            if (index % labelInterval == 0 || index == dailyTokens.lastIndex) {
                val label = daily.date.takeLast(5)
                Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenUsageDetailCard(
    usageManager: TokenUsageManager,
    dailyTokenUsage: List<com.lin.hippyagent.core.insights.DailyTokenUsage>,
    modifier: Modifier = Modifier
) {
    var summary by remember { mutableStateOf<TokenUsageSummary?>(null) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(LocalDate.now().minusDays(7)) }
    var endDate by remember { mutableStateOf(LocalDate.now()) }

    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    LaunchedEffect(startDate, endDate) {
        usageManager.dailyUsage.collect { dailyMap ->
            val filtered = dailyMap.filterKeys { date -> date in startDate..endDate }
            var totalInput = 0L
            var totalOutput = 0L
            var totalCalls = 0L
            var totalCacheRead = 0L
            var totalCacheWrite = 0L
            val byModel = mutableMapOf<String, ModelUsageSummary>()
            filtered.values.forEach { daySummary ->
                totalInput += daySummary.totalInputTokens
                totalOutput += daySummary.totalOutputTokens
                totalCalls += daySummary.totalCalls
                totalCacheRead += daySummary.totalCacheReadTokens
                totalCacheWrite += daySummary.totalCacheWriteTokens
                daySummary.byModel.forEach { (key, modelSummary) ->
                    val existing = byModel[key]
                    if (existing != null) {
                        byModel[key] = existing.copy(
                            inputTokens = existing.inputTokens + modelSummary.inputTokens,
                            outputTokens = existing.outputTokens + modelSummary.outputTokens,
                            calls = existing.calls + modelSummary.calls,
                            cacheReadTokens = existing.cacheReadTokens + modelSummary.cacheReadTokens,
                            cacheWriteTokens = existing.cacheWriteTokens + modelSummary.cacheWriteTokens
                        )
                    } else {
                        byModel[key] = modelSummary
                    }
                }
            }
            summary = TokenUsageSummary(
                totalInputTokens = totalInput,
                totalOutputTokens = totalOutput,
                totalCalls = totalCalls,
                totalCacheReadTokens = totalCacheRead,
                totalCacheWriteTokens = totalCacheWrite,
                byModel = byModel
            )
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.insights_token_consumption),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showStartDatePicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(startDate.format(formatter), fontSize = 12.sp, maxLines = 1)
                }
                Text(stringResource(R.string.insights_to), modifier = Modifier.align(Alignment.CenterVertically))
                OutlinedButton(
                    onClick = { showEndDatePicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(endDate.format(formatter), fontSize = 12.sp, maxLines = 1)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (dailyTokenUsage.isNotEmpty()) {
                TokenTrendChart(dailyTokenUsage)
                Spacer(Modifier.height(12.dp))
            }

            summary?.let { s ->
                TokenUsageSummaryCard(summary = s)

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.insights_by_model),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(8.dp))

                s.byModel.values.forEach { model ->
                    TokenUsageModelCard(model = model)
                    Spacer(Modifier.height(4.dp))
                }
            } ?: Text(
                text = stringResource(R.string.insights_no_stats),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            startDate = Instant.ofEpochMilli(it)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showStartDatePicker = false
                    }
                ) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showStartDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = endDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            endDate = Instant.ofEpochMilli(it)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showEndDatePicker = false
                    }
                ) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showEndDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun TokenUsageSummaryCard(
    summary: TokenUsageSummary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.insights_total),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TokenStatItem(
                    label = stringResource(R.string.insights_input_token),
                    value = tokenFormatNumber(summary.totalInputTokens)
                )
                TokenStatItem(
                    label = stringResource(R.string.insights_output_token),
                    value = tokenFormatNumber(summary.totalOutputTokens)
                )
                TokenStatItem(
                    label = stringResource(R.string.insights_total_calls),
                    value = tokenFormatNumber(summary.totalCalls)
                )
            }

            if (summary.totalCacheReadTokens > 0 || summary.totalCacheWriteTokens > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TokenStatItem(
                        label = stringResource(R.string.insights_cache_read),
                        value = tokenFormatNumber(summary.totalCacheReadTokens)
                    )
                    TokenStatItem(
                        label = stringResource(R.string.insights_cache_write),
                        value = tokenFormatNumber(summary.totalCacheWriteTokens)
                    )
                    TokenStatItem(label = "", value = "")
                }
            }
        }
    }
}

@Composable
private fun TokenUsageModelCard(
    model: ModelUsageSummary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${model.providerId} / ${model.modelName}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = stringResource(R.string.insights_call_times, model.calls),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TokenStatItem(
                    label = stringResource(R.string.insights_input),
                    value = tokenFormatNumber(model.inputTokens)
                )
                TokenStatItem(
                    label = stringResource(R.string.insights_output),
                    value = tokenFormatNumber(model.outputTokens)
                )
            }
        }
    }
}

@Composable
private fun TokenStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun tokenFormatNumber(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format("%.2fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.2fK", value / 1_000.0)
        else -> value.toString()
    }
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${String.format("%.1f", tokens / 1_000_000.0)}M"
    tokens >= 1_000 -> "${String.format("%.1f", tokens / 1_000.0)}K"
    else -> "$tokens"
}

@Composable
private fun weekdayName(day: Int): String = when (day) {
    1 -> stringResource(R.string.insights_mon); 2 -> stringResource(R.string.insights_tue); 3 -> stringResource(R.string.insights_wed); 4 -> stringResource(R.string.insights_thu)
    5 -> stringResource(R.string.insights_fri); 6 -> stringResource(R.string.insights_sat); 7 -> stringResource(R.string.insights_sun); else -> "-"
}


