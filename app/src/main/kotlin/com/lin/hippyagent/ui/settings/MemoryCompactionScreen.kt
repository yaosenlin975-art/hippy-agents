package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryCompactionScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var autoCompact by remember { mutableStateOf(true) }
    var compactThreshold by remember { mutableStateOf("10000") }
    var keepRecent by remember { mutableStateOf("100") }

    Scaffold(
        topBar = {
            HippyTopBar(
                title = "记忆压缩",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 说明
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("记忆压缩", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "自动压缩过长的记忆，保留重要信息，删除冗余内容，提高记忆检索效率。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 自动压缩开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动压缩", fontSize = 14.sp)
                        Text("当记忆超过阈值时自动压缩", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoCompact, onCheckedChange = { autoCompact = it })
                }
            }

            // 压缩阈值
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("压缩阈值（字符数）", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = compactThreshold,
                        onValueChange = { compactThreshold = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = autoCompact
                    )
                }
            }

            // 保留最近记忆
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("保留最近记忆（条数）", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keepRecent,
                        onValueChange = { keepRecent = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = autoCompact
                    )
                }
            }
        }
    }
}

