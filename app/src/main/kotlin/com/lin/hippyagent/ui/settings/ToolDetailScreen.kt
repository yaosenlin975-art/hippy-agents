package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(
    toolName: String,
    toolRegistry: ToolRegistry,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toolDefinition = remember { toolRegistry.getToolDefinition(toolName) }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = toolName,
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        if (toolDefinition == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.tools_not_exist), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.tools_basic_info), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.tools_name_label_fmt, toolDefinition.name), fontSize = 14.sp)
                            Text(stringResource(R.string.tools_desc_label_fmt, toolDefinition.description), fontSize = 14.sp)
                        }
                    }
                }

                item {
                    Text(stringResource(R.string.tools_params), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                items(toolDefinition.parameters.entries.toList()) { (paramName, param) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(paramName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(stringResource(R.string.tools_type_label_fmt, param.type), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.tools_desc_label_fmt, param.description), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.tools_required_label_fmt, if (param.required) stringResource(R.string.tools_required_yes) else stringResource(R.string.tools_required_no)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
