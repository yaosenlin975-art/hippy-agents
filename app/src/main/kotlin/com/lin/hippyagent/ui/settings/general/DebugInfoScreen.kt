package com.lin.hippyagent.ui.settings.general

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.debug.DebugInfoCollector
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugInfoScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = remember { getDebugInfo(context) }
    Scaffold(topBar = { HippyTopBar(title = stringResource(R.string.debug_info), showBackButton = true, onBackClick = onBackClick) }) { padding ->
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
        add(ctx.getString(R.string.debug_app_info) to info.appInfo.toList())
        add(ctx.getString(R.string.debug_device) to info.deviceInfo.toList())
        add(ctx.getString(R.string.debug_runtime) to info.runtimeInfo.toList())
        add(ctx.getString(R.string.debug_storage) to info.storageInfo.toList())
        add(ctx.getString(R.string.debug_data) to info.dataInfo.toList())
        if (info.recentErrors.isNotEmpty()) {
            add(ctx.getString(R.string.debug_recent_errors) to info.recentErrors.map { ctx.getString(R.string.debug_error_label) to it })
        }
    }
}



