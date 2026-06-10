package com.lin.hippyagent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lin.hippyagent.core.agent.mode.ModeOnboarding
import com.lin.hippyagent.core.notification.InAppMessageBubbleHost
import com.lin.hippyagent.ui.navigation.AppNavigation
import com.lin.hippyagent.ui.theme.HippyTheme
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("hippy_settings", android.content.Context.MODE_PRIVATE)
        val lang = prefs.getString("language", null)
        val context = if (lang != null) {
            val locale = java.util.Locale(lang)
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }
        super.attachBaseContext(context)
    }

    /**
     * 基础权限 — 首次启动时请求，应用核心功能所需
     */
    private val basicPermissions: Array<String>
        get() {
            val perms = mutableListOf<String>(
                // 网络状态（部分设备需要显式请求）
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    /**
     * 危险权限 — 不再首次启动时请求，改为工具执行时按需请求
     */
    private val dangerousPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isNotEmpty()) {
                timber.log.Timber.w("以下权限未被授予: $denied")
            } else {
                timber.log.Timber.i("所有危险权限已授予")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBasicPermissions()
        get<ModeOnboarding>().showIfNeeded(this)
        val deepLinkSessionId = intent.getStringExtra("deep_link_session_id")
        setContent {
            HippyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(deepLinkSessionId = deepLinkSessionId)
                        InAppMessageBubbleHost(context = LocalContext.current)
                    }
                }
            }
        }
    }

    private fun requestBasicPermissions() {
        val notGranted = basicPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            timber.log.Timber.i("所有基础权限已授予，无需请求")
        }
    }

    private fun requestDangerousPermissions() {
        val notGranted = dangerousPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            timber.log.Timber.i("所有危险权限已授予，无需请求")
        }
    }

    companion object {
        /**
         * 供外部（如工具执行）调用的按需权限请求方法。
         * 通过 Activity 实例请求指定权限。
         */
        fun requestPermissionIfNeeded(activity: android.app.Activity, vararg permissions: String) {
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty() && activity is MainActivity) {
                activity.permissionLauncher.launch(notGranted.toTypedArray())
            }
        }
    }
}

