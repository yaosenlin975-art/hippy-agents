package com.lin.hippyagent.ui.trace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.data.TraceSummaryRow
import org.koin.androidx.viewmodel.ext.android.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceListScreen(
    viewModel: TraceViewModel,
    onTraceClick: (String) -> Unit,
    onOpenStats: () -> Unit
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadTraceList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("执行追踪") },
                actions = {
                    TextButton(onClick = onOpenStats) { Text("统计") }
                    IconButton(onClick = { viewModel.clearAllTraces() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("错误：${state.error}", color = MaterialTheme.colorScheme.error)
                }
            }
            state.traces.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("暂无追踪记录\n开启追踪后发起对话即可生成", style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = state.traces,
                        key = { it.traceId }
                    ) { trace ->
                        TraceItem(trace = trace, onClick = { onTraceClick(trace.traceId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceItem(trace: TraceSummaryRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trace.traceId.take(8),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${trace.totalDurationMs}ms",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    text = "Span: ${trace.spanCount}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                if (trace.errorCount > 0) {
                    Text(
                        text = "错误: ${trace.errorCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
