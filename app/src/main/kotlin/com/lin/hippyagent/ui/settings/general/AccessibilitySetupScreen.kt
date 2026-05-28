package com.lin.hippyagent.ui.settings.general

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.accessibility.PhoneControlAccessibilityService
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilitySetupScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var isServiceEnabled by remember { mutableStateOf(checkAccessibilityEnabled(ctx)) }

    Scaffold(topBar = { HippyTopBar(title = "无障碍手机操控", showBackButton = true, onBackClick = onBackClick) }) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(16.dp)) }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isServiceEnabled) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isServiceEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isServiceEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isServiceEnabled) "无障碍服务已启用" else "无障碍服务未启用",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isServiceEnabled) "Agent 可以感知和操控手机屏幕" else "需要启用后 Agent 才能操控手机",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }

            item {
                Text("开启步骤", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        StepItem("1", "点击下方按钮跳转到系统无障碍设置")
                        StepItem("2", "在列表中找到「Hippy」")
                        StepItem("3", "点击进入并开启服务")
                        StepItem("4", "返回本页面确认状态")
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                Button(
                    onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("前往系统无障碍设置", fontSize = 15.sp)
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                OutlinedButton(
                    onClick = { isServiceEnabled = checkAccessibilityEnabled(ctx) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("刷新状态", fontSize = 15.sp)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                Text("安全说明", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("银行/支付类 App 默认禁止操控")
                        InfoRow("输入密码/支付金额等高风险操作需用户确认")
                        InfoRow("所有操作均有审计日志可追溯")
                        InfoRow("可随时在系统设置中关闭服务")
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun StepItem(step: String, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(step, color = Color.White, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun InfoRow(text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("•", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val serviceName = "${context.packageName}/${PhoneControlAccessibilityService::class.java.canonicalName}"
    try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(serviceName, ignoreCase = true)) return true
        }
    } catch (_: Exception) {}
    return PhoneControlAccessibilityService.isRunning()
}

