package com.lin.hippyagent.ui.settings.general

import android.app.Activity
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.storage.StorageManager
import com.lin.hippyagent.ui.components.HippyTopBar
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataStorageScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    storageManager: StorageManager = koinInject(),
    linuxManager: com.lin.hippyagent.core.linux.LinuxManager = koinInject()
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = remember { mutableStateOf(getStorageInfo(ctx, storageManager)) }
    val isSafEnabled = remember { mutableStateOf(storageManager.isSafEnabled()) }
    val isMigrating = remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }

    // SAF 目录选择器 — 一键自动挂载
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            storageManager.setSafUri(uri)
            isSafEnabled.value = true
            isMigrating.value = true
            scope.launch(Dispatchers.IO) {
                val hasData = storageManager.safHasExistingData()
                val result = if (hasData) {
                    storageManager.syncSafToInternal()
                    storageManager.migrateToSaf()
                } else {
                    storageManager.migrateToSaf()
                }
                try { linuxManager.initialize() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    isMigrating.value = false
                    info.value = getStorageInfo(ctx, storageManager)
                    result.onSuccess { count ->
                        Toast.makeText(ctx, "挂载完成：$count 个文件已同步", Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Timber.e(e, "Auto mount failed")
                        Toast.makeText(ctx, "挂载失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(topBar = { HippyTopBar(title = "数据存储", showBackButton = true, onBackClick = onBackClick) }) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(8.dp)) }

            // 存储概览
            item { SectionHeader("存储概览") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("总容量", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${info.value.totalGB}GB", fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { info.value.usedPct },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("已用 ${info.value.usedGB}GB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("可用 ${info.value.availGB}GB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 外部存储设置
            item { SectionHeader("外部存储（卸载保护）") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isSafEnabled.value) Icons.Default.Check
                                else Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isSafEnabled.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isSafEnabled.value) "外部存储已授权" else "数据存储在内部存储",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isSafEnabled.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isSafEnabled.value)
                                "数据已迁移到外部目录，卸载应用不会丢失数据。"
                            else
                                "当前数据存储在应用内部目录，卸载应用将丢失所有数据（配置、聊天记录、记忆）。建议授权外部存储目录。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(12.dp))

                        if (!isSafEnabled.value) {
                            // 未授权：显示授权按钮
                            Button(
                                onClick = { safLauncher.launch(null) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("选择外部存储目录")
                            }
                        } else {
                            // 已授权：显示操作按钮
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        isMigrating.value = true
                                        scope.launch(Dispatchers.IO) {
                                            val hasData = storageManager.safHasExistingData()
                                            val result = if (hasData) {
                                                storageManager.syncSafToInternal()
                                                storageManager.migrateToSaf()
                                            } else {
                                                storageManager.migrateToSaf()
                                            }
                                            try { linuxManager.initialize() } catch (_: Exception) {}
                                            withContext(Dispatchers.Main) {
                                                isMigrating.value = false
                                                info.value = getStorageInfo(ctx, storageManager)
                                                result.onSuccess { count ->
                                                    Toast.makeText(ctx, "挂载完成：$count 个文件已同步", Toast.LENGTH_LONG).show()
                                                }.onFailure { e ->
                                                    Toast.makeText(ctx, "挂载失败：${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isMigrating.value
                                ) {
                                    if (isMigrating.value) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("重新挂载")
                                }
                                OutlinedButton(
                                    onClick = { showDisableDialog = true },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("关闭外部存储")
                                }
                            }
                        }
                    }
                }
            }

            // 应用数据
            item { SectionHeader("应用数据") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        listOf(
                            "智能体数据" to info.value.agents,
                            "会话记录" to info.value.sessions,
                            "核心文件" to info.value.coreFiles,
                            "数据库" to info.value.db
                        ).forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(k, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // 数据位置说明
            item { SectionHeader("存储位置说明") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        val items = listOf(
                            "工作区" to "",
                            "敏感数据" to "secret/",
                            "备份" to ".backups/",
                            "数据库" to "Room（内部存储）"
                        )
                        items.forEach { (name, path) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text(path, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "注意：Room 数据库文件必须保留在内部存储（Android 限制）。配置、聊天记录、记忆等文件可存储在外部目录。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // 关闭外部存储确认对话框
    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("关闭外部存储") },
            text = {
                Text("关闭外部存储后，新数据将写入内部存储。外部存储中的已有数据不会被删除。\n\n警告：关闭后卸载应用仍会丢失新产生的数据。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        storageManager.setSafUri(null)
                        isSafEnabled.value = false
                        showDisableDialog = false
                        info.value = getStorageInfo(ctx, storageManager)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确认关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

private data class StorageInfo(
    val totalGB: Long, val availGB: Long, val usedGB: Long, val usedPct: Float,
    val agents: String, val sessions: String, val coreFiles: String, val db: String,
    val isSafEnabled: Boolean
)

private fun fmt(b: Long) = when {
    b < 1024 -> "${b}B"
    b < 1048576 -> "${b / 1024}KB"
    b < 1073741824 -> "${"%.1f".format(b / 1048576.0)}MB"
    else -> "${"%.1f".format(b / 1073741824.0)}GB"
}

private fun dirSize(d: File): Long {
    if (!d.exists()) return 0
    var s = 0L
    d.listFiles()?.forEach { s += if (it.isDirectory) dirSize(it) else it.length() }
    return s
}

private fun getStorageInfo(ctx: android.content.Context, storageManager: StorageManager): StorageInfo {
    val stat = StatFs(Environment.getDataDirectory().path)
    val t = stat.totalBytes / 1073741824
    val a = stat.availableBytes / 1073741824
    val u = t - a
    val copaw = ctx.filesDir
    val agents = File(copaw, "agents")
    val ws = File(copaw, "workspaces")
    var sess = 0L
    var core = 0L
    if (ws.exists()) ws.listFiles()?.forEach { d ->
        sess += dirSize(File(d, "sessions"))
        d.listFiles()?.filter { it.isDirectory && it.name != "sessions" }?.forEach { core += dirSize(it) }
    }
    return StorageInfo(
        t, a, u, if (t > 0) (u.toFloat() / t).coerceIn(0f, 1f) else 0f,
        fmt(dirSize(agents)), fmt(sess), fmt(core),
        fmt(dirSize(File(ctx.getDatabasePath("dummy").parent ?: ""))),
        storageManager.isSafEnabled()
    )
}



