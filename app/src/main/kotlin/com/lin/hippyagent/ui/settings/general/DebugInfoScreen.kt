package com.lin.hippyagent.ui.settings.general

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.debug.DebugInfoCollector
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugInfoScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = remember { getDebugInfo(context) }
    Scaffold(topBar = { HippyTopBar(title = "调试信息", showBackButton = true, onBackClick = onBackClick) }) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            info.forEach { (section, items) ->
                item { Text(section, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            items.forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun getDebugInfo(ctx: android.content.Context): List<Pair<String, List<Pair<String, String>>>> {
    val info = DebugInfoCollector(ctx).collect()
    return buildList {
        add("应用信息" to info.appInfo.toList())
        add("设备" to info.deviceInfo.toList())
        add("运行" to info.runtimeInfo.toList())
        add("存储" to info.storageInfo.toList())
        add("数据" to info.dataInfo.toList())
        if (info.recentErrors.isNotEmpty()) {
            add("最近错误" to info.recentErrors.map { "错误" to it })
        }
    }
}



