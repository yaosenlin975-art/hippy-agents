package com.lin.hippyagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
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
                title = stringResource(R.string.memory_compaction),
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
                    Text(stringResource(R.string.memory_compaction), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.memory_compaction_desc),
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
                        Text(stringResource(R.string.memory_auto_compact), fontSize = 14.sp)
                        Text(stringResource(R.string.memory_auto_compact_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoCompact, onCheckedChange = { autoCompact = it })
                }
            }

            // 压缩阈值
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.memory_compact_threshold), fontSize = 14.sp)
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
                    Text(stringResource(R.string.memory_keep_recent), fontSize = 14.sp)
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

