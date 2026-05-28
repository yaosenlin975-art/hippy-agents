package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Text("工具不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 基本信息
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("基本信息", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("名称: ${toolDefinition.name}", fontSize = 14.sp)
                            Text("描述: ${toolDefinition.description}", fontSize = 14.sp)
                        }
                    }
                }

                // 参数列表
                item {
                    Text("参数", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                items(toolDefinition.parameters.entries.toList()) { (paramName, param) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(paramName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("类型: ${param.type}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("描述: ${param.description}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("必需: ${if (param.required) "是" else "否"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

