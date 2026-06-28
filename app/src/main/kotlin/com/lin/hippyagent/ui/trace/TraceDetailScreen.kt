package com.lin.hippyagent.ui.trace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.data.TraceSpanEntity

@Composable
fun TraceDetailScreen(
    traceId: String,
    viewModel: TraceViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.detailState.collectAsStateWithLifecycle()

    LaunchedEffect(traceId) {
        viewModel.loadTraceDetail(traceId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trace ${traceId.take(8)}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
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
            else -> {
                val roots = remember(state.spans) {
                    state.spans.filter { it.parentSpanId == null }
                }
                val childrenMap = remember(state.spans) {
                    state.spans.groupBy { it.parentSpanId }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = roots, key = { it.id }) { root ->
                        SpanNode(span = root, childrenMap = childrenMap, depth = 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpanNode(
    span: TraceSpanEntity,
    childrenMap: Map<String?, List<TraceSpanEntity>>,
    depth: Int
) {
    var expanded by remember { mutableStateOf(true) }
    val children = childrenMap[span.id] ?: emptyList()

    Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (children.isNotEmpty()) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            } else {
                Spacer(Modifier.size(24.dp))
            }
            Text(
                text = "${span.type} (${span.durationMs}ms)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (span.error != null) {
                Text("❌", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            text = span.propsJson.take(120),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp)
        )
        if (expanded && children.isNotEmpty()) {
            children.forEach { child ->
                SpanNode(span = child, childrenMap = childrenMap, depth = depth + 1)
            }
        }
    }
}
