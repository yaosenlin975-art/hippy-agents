package com.lin.hippyagent.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.components.HippyTopBar
import androidx.compose.ui.res.stringResource

@Immutable
private data class PermissionItem(
    val key: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val categoryRes: Int,
    val dangerousApi: String? = null
)

private val PERMISSION_LIST = listOf(
    PermissionItem("notification", R.string.perm_notification, R.string.perm_notification_desc, R.string.perm_category_communication,
        dangerousApi = null),
    PermissionItem("camera", R.string.perm_camera, R.string.perm_camera_desc, R.string.perm_category_hardware,
        dangerousApi = Manifest.permission.CAMERA),
    PermissionItem("microphone", R.string.perm_microphone, R.string.perm_microphone_desc, R.string.perm_category_hardware,
        dangerousApi = Manifest.permission.RECORD_AUDIO),
    PermissionItem("location", R.string.perm_location, R.string.perm_location_desc, R.string.perm_category_hardware,
        dangerousApi = Manifest.permission.ACCESS_FINE_LOCATION),
    PermissionItem("contacts", R.string.perm_contacts, R.string.perm_contacts_desc, R.string.perm_category_communication,
        dangerousApi = Manifest.permission.READ_CONTACTS),
    PermissionItem("sms", R.string.perm_sms, R.string.perm_sms_desc, R.string.perm_category_communication,
        dangerousApi = Manifest.permission.READ_SMS),
    PermissionItem("phone", R.string.perm_phone, R.string.perm_phone_desc, R.string.perm_category_communication,
        dangerousApi = Manifest.permission.CALL_PHONE),
    PermissionItem("calendar", R.string.perm_calendar, R.string.perm_calendar_desc, R.string.perm_category_data,
        dangerousApi = Manifest.permission.READ_CALENDAR),
    PermissionItem("storage", R.string.perm_storage, R.string.perm_storage_desc, R.string.perm_category_data,
        dangerousApi = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE),
    PermissionItem("bluetooth", R.string.perm_bluetooth, R.string.perm_bluetooth_desc, R.string.perm_category_hardware,
        dangerousApi = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
        else Manifest.permission.BLUETOOTH),
    PermissionItem("wifi", R.string.perm_wifi, R.string.perm_wifi_desc, R.string.perm_category_hardware,
        dangerousApi = Manifest.permission.ACCESS_WIFI_STATE),
    PermissionItem("overlay", R.string.perm_overlay, R.string.perm_overlay_desc, R.string.perm_category_system,
        dangerousApi = null),
    PermissionItem("alarm", R.string.perm_alarm, R.string.perm_alarm_desc, R.string.perm_category_system,
        dangerousApi = null),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionCenterScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val permissionStatuses = remember { mutableStateMapOf<String, Boolean>() }

    var pendingPermissionKey by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingPermissionKey?.let { key ->
            val item = PERMISSION_LIST.find { it.key == key }
            if (item != null) {
                permissionStatuses[key] = checkPermission(context, item)
            }
        }
        pendingPermissionKey = null
    }

    val checkAllPermissions: () -> Unit = {
        PERMISSION_LIST.forEach { item ->
            permissionStatuses[item.key] = checkPermission(context, item)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        checkAllPermissions()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                checkAllPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grantedCount = permissionStatuses.count { it.value }
    val totalCount = PERMISSION_LIST.size

    Scaffold(
        topBar = {
            HippyTopBar(
                title = stringResource(R.string.perm_center_title),
                onBackClick = onBackClick
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.perm_granted_count, grantedCount, totalCount),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.perm_center_hint),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            val categories = PERMISSION_LIST.groupBy { it.categoryRes }
            categories.forEach { (categoryRes, items) ->
                item {
                    Text(
                        stringResource(categoryRes),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                }
                items.forEach { item ->
                    item {
                        val isGranted = permissionStatuses[item.key] ?: false
                        val statusColor = if (isGranted) Color(0xFF34C759)
                        else MaterialTheme.colorScheme.outline
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    if (item.dangerousApi != null && activity != null) {
                                        pendingPermissionKey = item.key
                                        permissionLauncher.launch(item.dangerousApi)
                                    } else {
                                        requestPermission(context, item)
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(item.nameRes),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        stringResource(item.descriptionRes),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .padding(end = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .padding(0.dp)
                                    ) {
                                        CircularGrantedDot(isGranted, statusColor)
                                    }
                                }
                                Text(
                                    if (isGranted) stringResource(R.string.perm_granted) else stringResource(R.string.perm_not_granted),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColor
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun CircularGrantedDot(isGranted: Boolean, statusColor: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .padding(0.dp)
    ) {
        if (isGranted) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(5.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor)
            ) {}
        } else {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(5.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(containerColor = statusColor)
                    ) {}
                }
            }
        }
    }
}

private fun checkPermission(context: Context, item: PermissionItem): Boolean {
    return when (item.key) {
        "overlay" -> Settings.canDrawOverlays(context)
        "alarm" -> {
            if (Build.VERSION.SDK_INT >= 31) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                alarmManager?.canScheduleExactAlarms() ?: true
            } else true
        }
        "notification" -> {
            try {
                val enabledListeners = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: ""
                enabledListeners.contains(context.packageName, ignoreCase = true)
            } catch (_: Exception) { false }
        }
        else -> {
            item.dangerousApi?.let { api ->
                ContextCompat.checkSelfPermission(context, api) == PackageManager.PERMISSION_GRANTED
            } ?: true
        }
    }
}

private fun requestPermission(context: Context, item: PermissionItem) {
    val intent = when (item.key) {
        "overlay" -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null)
        )
        "alarm" -> if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else Intent(Settings.ACTION_SETTINGS)
        "notification" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        else -> return // 危险权限由 launcher 处理，不应走到这里
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {}
}
