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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.accessibility.PhoneControlAccessibilityService
import com.lin.hippyagent.ui.components.HippyTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilitySetupScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var isServiceEnabled by remember { mutableStateOf(checkAccessibilityEnabled(ctx)) }

    Scaffold(topBar = { HippyTopBar(title = stringResource(R.string.accessibility_phone_control), showBackButton = true, onBackClick = onBackClick) }) { padding ->
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
                                text = if (isServiceEnabled) stringResource(R.string.accessibility_service_enabled) else stringResource(R.string.accessibility_service_disabled_title),
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isServiceEnabled) stringResource(R.string.accessibility_can_control) else stringResource(R.string.accessibility_need_enable),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }

            item {
                Text(stringResource(R.string.accessibility_steps_title), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        StepItem("1", stringResource(R.string.accessibility_step_1))
                        StepItem("2", stringResource(R.string.accessibility_step_2))
                        StepItem("3", stringResource(R.string.accessibility_step_3))
                        StepItem("4", stringResource(R.string.accessibility_step_4))
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
                    Text(stringResource(R.string.accessibility_go_to_settings), fontSize = 15.sp)
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                OutlinedButton(
                    onClick = { isServiceEnabled = checkAccessibilityEnabled(ctx) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.accessibility_refresh_status), fontSize = 15.sp)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                Text(stringResource(R.string.accessibility_safety_title), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow(stringResource(R.string.accessibility_safety_bank))
                        InfoRow(stringResource(R.string.accessibility_safety_password))
                        InfoRow(stringResource(R.string.accessibility_safety_audit))
                        InfoRow(stringResource(R.string.accessibility_safety_disable))
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
