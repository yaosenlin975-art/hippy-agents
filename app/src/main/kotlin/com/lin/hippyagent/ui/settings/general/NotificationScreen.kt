package com.lin.hippyagent.ui.settings.general

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val prefs = remember { ctx.getSharedPreferences("hippy_settings", Context.MODE_PRIVATE) }

    // 从 SharedPreferences 读取持久化的通知开关状态
    var enabled by remember { mutableStateOf(prefs.getBoolean("notifications_enabled", true)) }
    var heartbeat by remember { mutableStateOf(prefs.getBoolean("notification_heartbeat", true)) }
    var message by remember { mutableStateOf(prefs.getBoolean("notification_message", true)) }
    var error by remember { mutableStateOf(prefs.getBoolean("notification_error", true)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted
        prefs.edit().putBoolean("notifications_enabled", granted).apply()
        if (granted) createChannel(ctx)
    }

    Scaffold(topBar = { HippyTopBar(title = "通知", showBackButton = true, onBackClick = onBackClick) }) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(8.dp)); Text("通知设置", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("启用通知", fontSize = 15.sp); Text("需要系统通知权限", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(checked = enabled, onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@Switch
                            }
                            enabled = on
                            prefs.edit().putBoolean("notifications_enabled", on).apply()
                            if (on) createChannel(ctx)
                        }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
                    }
                }
            }
            if (enabled) {
                item { Text("通知类型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column {
                            NotiRow("心跳通知", "Agent 心跳执行结果", heartbeat) {
                                heartbeat = it
                                prefs.edit().putBoolean("notification_heartbeat", it).apply()
                            }
                            NotiRow("消息通知", "收到新消息时提醒", message) {
                                message = it
                                prefs.edit().putBoolean("notification_message", it).apply()
                            }
                            NotiRow("错误通知", "Agent 运行出错时提醒", error) {
                                error = it
                                prefs.edit().putBoolean("notification_error", it).apply()
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun NotiRow(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp); Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

private fun createChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val ch = NotificationChannel("hippy_default", "Hippy", NotificationManager.IMPORTANCE_DEFAULT)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}

