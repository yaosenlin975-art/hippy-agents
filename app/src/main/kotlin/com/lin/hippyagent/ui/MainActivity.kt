package com.lin.hippyagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lin.hippyagent.core.agent.mode.ModeOnboarding
import com.lin.hippyagent.core.notification.InAppMessageBubbleHost
import com.lin.hippyagent.ui.entry.AgentEntryRouter
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
                Manifest.permission.RECORD_AUDIO,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
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

    // 用 mutableStateOf 暴露 agent 入口参数，onNewIntent 时更新触发 Compose 重组（替代 recreate，避免丢失非 rememberSaveable 状态）
    private val agentActionState = mutableStateOf<String?>(null)
    private val agentPromptState = mutableStateOf<String?>(null)
    private val quickAskState = mutableStateOf(false)
    // 入口 token：每次 onNewIntent 递增，强制 AppNavigation 的 LaunchedEffect 重新触发（避免相同 extra 重复点击不响应）
    private val entryTokenState = mutableStateOf(0)
    // deep_link_session_id：onNewIntent 传入新值时刷新，触发 AppNavigation 重组跳转目标会话
    private val deepLinkSessionIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBasicPermissions()
        get<ModeOnboarding>().showIfNeeded(this)
        applyAgentEntryIntent(intent)
        applyDeepLinkSessionId(intent)
        setContent {
            HippyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(
                            deepLinkSessionId = deepLinkSessionIdState.value,
                            agentAction = agentActionState.value,
                            agentPrompt = agentPromptState.value,
                            quickAsk = quickAskState.value,
                            entryToken = entryTokenState.value
                        )
                        InAppMessageBubbleHost(context = LocalContext.current)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyAgentEntryIntent(intent)
        applyDeepLinkSessionId(intent)
    }

    private fun applyAgentEntryIntent(intent: Intent) {
        val agentAction = intent.getStringExtra(AgentEntryRouter.EXTRA_AGENT_ACTION)
        val agentPrompt = intent.getStringExtra(AgentEntryRouter.EXTRA_PROMPT)
        val quickAsk = intent.getBooleanExtra(AgentEntryRouter.EXTRA_QUICK_ASK, false)
        agentActionState.value = agentAction
        agentPromptState.value = agentPrompt
        quickAskState.value = quickAsk
        entryTokenState.value = entryTokenState.value + 1
        // open_chat / create_cron 由 AppNavigation 的 LaunchedEffect 消费（避免 AgentEntryRouter 循环启动 MainActivity）
    }

    private fun applyDeepLinkSessionId(intent: Intent) {
        deepLinkSessionIdState.value = intent.getStringExtra("deep_link_session_id")
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

