package com.lin.hippyagent.ui.trace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.data.SpanTypeStatsRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceStatsScreen(
    viewModel: TraceViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.statsState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadStats(state.timeRangeDays)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trace_stats_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1, 7, 30).forEach { days ->
                    FilterChip(
                        selected = state.timeRangeDays == days,
                        onClick = { viewModel.loadStats(days) },
                        label = { Text(if (days == 1) "24h" else "${days}d") }
                    )
                }
            }
            HorizontalDivider()
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.error_format, state.error ?: ""), color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = state.stats, key = { it.type }) { stat ->
                            StatCard(stat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(stat: SpanTypeStatsRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stat.type, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row {
                Text(stringResource(R.string.trace_count_format, stat.count), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.trace_avg_format, stat.avgDurationMs.toInt()), style = MaterialTheme.typography.bodySmall)
            }
            if (stat.errorCount > 0) {
                Text(
                    stringResource(R.string.trace_error_percent_format, stat.errorCount, (stat.errorCount.toFloat() / stat.count * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
