package com.lin.hippyagent.ui.trace

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin.hippyagent.R
import com.lin.hippyagent.data.TraceSpanEntity

@OptIn(ExperimentalMaterial3Api::class)
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
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) } }
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
                    Text(stringResource(R.string.error_format, state.error ?: ""), color = MaterialTheme.colorScheme.error)
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
    var showDetail by remember { mutableStateOf(false) }
    val children = childrenMap[span.id] ?: emptyList()

    Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (children.isNotEmpty()) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand)
                    )
                }
            } else {
                Spacer(Modifier.size(24.dp))
            }
            Text(
                text = "${span.type} (${span.durationMs}ms)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).clickable { showDetail = !showDetail }
            )
            if (span.error != null) {
                Text("❌", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            text = summarizeProps(span),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp)
        )
        if (showDetail && span.type == "LLM_CALL") {
            LlmDetailPanel(span)
        }
        if (expanded && children.isNotEmpty()) {
            children.forEach { child ->
                SpanNode(span = child, childrenMap = childrenMap, depth = depth + 1)
            }
        }
    }
}

@Composable
private fun LlmDetailPanel(span: TraceSpanEntity) {
    Card(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            val props = parseProps(span.propsJson)
            Text(stringResource(R.string.trace_model_format, props["modelId"] ?: ""), style = MaterialTheme.typography.bodySmall)
            Text("Provider: ${props["providerId"]}", style = MaterialTheme.typography.bodySmall)
            Text("Prompt tokens: ${props["promptTokens"]}", style = MaterialTheme.typography.bodySmall)
            Text("Completion tokens: ${props["completionTokens"]}", style = MaterialTheme.typography.bodySmall)
            Text("Finish reason: ${props["finishReason"]}", style = MaterialTheme.typography.bodySmall)
            props["messageCount"]?.let { Text(stringResource(R.string.trace_message_count_format, it), style = MaterialTheme.typography.bodySmall) }
            props["responseChars"]?.let { Text(stringResource(R.string.trace_response_chars_format, it), style = MaterialTheme.typography.bodySmall) }
            (props["requestMessages"] as? String)?.let {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.trace_request_label), style = MaterialTheme.typography.labelSmall)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            (props["responseText"] as? String)?.let {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.trace_response_label), style = MaterialTheme.typography.labelSmall)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun summarizeProps(span: TraceSpanEntity): String {
    val props = parseProps(span.propsJson)
    return when (span.type) {
        "LLM_CALL" -> "model=${props["modelId"]}, tokens=${props["promptTokens"]}→${props["completionTokens"]}"
        "TOOL_CALL" -> "tool=${props["toolName"]}, retry=${props["retryCount"] ?: 0}"
        "SKILL_MATCH" -> "winner=${props["winner"]}"
        "MEMORY_RETRIEVAL" -> "retriever=${props["retrieverType"]}, results=${props["results"]}"
        "CONTEXT_COMPACTION" -> "before=${props["beforeTokens"]}→after=${props["afterTokens"]}"
        "AGENT_LOOP" -> "agent=${props["agentId"]}, iter=${props["iterationCount"]}"
        else -> span.propsJson.take(80)
    }
}

private fun parseProps(json: String): Map<String, Any> {
    if (json.isBlank()) return emptyMap()
    return runCatching {
        val obj = org.json.JSONObject(json)
        obj.keys().asSequence().associateWith { obj.get(it) }
    }.getOrDefault(emptyMap())
}
