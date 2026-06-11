package com.lin.hippyagent.ui.chat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lin.hippyagent.R
import com.lin.hippyagent.core.voice.AndroidBuiltinTranscriber

/**
 * 集中式麦克风权限处理：供 ChatScreen / GroupChatScreen 等需要语音输入的 Composable 复用。
 * 解决了此前在两个屏幕中各写一份 launcher 导致反射调用、拒绝回退、设置跳转逻辑可能漂移的问题。
 *
 * 关键点：
 * - 授权成功后立刻调用 [AndroidBuiltinTranscriber.notifyAppOps] 反射修复
 *   MIUI/HyperOS 上 AppOps 缓存导致的 SpeechRecognizer ERROR_INSUFFICIENT_PERMISSIONS。
 * - 永久拒绝（系统不再弹窗）时给出"去设置"对话框，否则只显示轻量 Toast。
 */
data class MicPermissionHandler(
    val requestMicPermission: () -> Unit,
    val showRationaleDialog: Boolean,
    val dismissRationaleDialog: () -> Unit,
)

@Composable
fun rememberMicPermissionHandler(
    onGranted: () -> Unit,
): MicPermissionHandler {
    val context = LocalContext.current
    var showRationaleDialog by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            AndroidBuiltinTranscriber.notifyAppOps(context)
            onGranted()
            return@rememberLauncherForActivityResult
        }
        val activity = context as? Activity
        val permanentlyDenied = activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.RECORD_AUDIO)
        if (permanentlyDenied) {
            showRationaleDialog = true
        } else {
            Toast.makeText(context, context.getString(R.string.chat_mic_permission_needed), Toast.LENGTH_SHORT).show()
        }
    }

    val requestMicPermission: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AndroidBuiltinTranscriber.notifyAppOps(context)
            onGranted()
        } else {
            launcher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    return MicPermissionHandler(
        requestMicPermission = requestMicPermission,
        showRationaleDialog = showRationaleDialog,
        dismissRationaleDialog = { showRationaleDialog = false },
    )
}

@Composable
fun MicPermissionRationaleDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_mic_permission_title)) },
        text = { Text(stringResource(R.string.chat_mic_permission_denied)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }) { Text(stringResource(R.string.chat_go_to_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
