package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.tools.BuiltinToolNames
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsListScreen(
    toolRegistry: ToolRegistry,
    onBackClick: () -> Unit,
    onToolClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val allToolDefinitions = remember { toolRegistry.getAllDefinitions() }
    var searchQuery by remember { mutableStateOf("") }

    val filteredDefinitions = remember(allToolDefinitions, searchQuery) {
        if (searchQuery.isBlank()) allToolDefinitions
        else {
            val q = searchQuery.lowercase()
            allToolDefinitions.filter { def ->
                val cnName = BuiltinToolNames.getDisplayName(def.name)
                def.name.lowercase().contains(q) ||
                    cnName.contains(q) ||
                    def.description.lowercase().contains(q)
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                HippyTopBar(
                    title = stringResource(R.string.settings_tools_list),
                    showBackButton = true,
                    onBackClick = onBackClick
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.tools_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
            }
        }
    ) { padding ->
        if (filteredDefinitions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isNotBlank()) stringResource(R.string.tools_no_match) else stringResource(R.string.agent_no_tools),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredDefinitions, key = { it.name }) { definition ->
                    ToolItem(definition, onClick = { onToolClick(definition.name) })
                }
            }
        }
    }
}

@Composable
private fun ToolItem(definition: ToolDefinition, onClick: () -> Unit) {
    val cnName = BuiltinToolNames.getDisplayName(definition.name)
    val displayName = buildAnnotatedString {
        if (cnName.isNotEmpty()) {
            append(cnName)
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontSize = 11.sp, fontWeight = FontWeight.Normal)) {
                append("  ${definition.name}")
            }
        } else {
            append(definition.name)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(definition.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.tools_params_count, definition.parameters.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
