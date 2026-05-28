package com.lin.hippyagent.di

import android.content.Context
import com.lin.hippyagent.core.security.PermissionManager
import com.lin.hippyagent.core.tools.FileLockManager
import com.lin.hippyagent.core.tools.ToolInitializer
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.core.plugin.PluginManager
import com.lin.hippyagent.core.skill.builtin.SkillScriptExecutor
import com.lin.hippyagent.core.skill.SkillManager
import com.lin.hippyagent.core.skill.SkillLifecycleManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val toolModule = module {
    single {
        val ctx = androidContext()
        val permissionManager = getOrNull<PermissionManager>()
        ToolRegistry().also { registry ->
            registry.permissionChecker = { permission ->
                if (permission in ToolRegistry.CUSTOM_PERMISSIONS) {
                    permissionManager?.isCustomToolApprovedSync(permission) ?: false
                } else {
                    val androidPermission = when (permission) {
                        "CAMERA" -> android.Manifest.permission.CAMERA
                        "RECORD_AUDIO" -> android.Manifest.permission.RECORD_AUDIO
                        "ACCESS_FINE_LOCATION" -> android.Manifest.permission.ACCESS_FINE_LOCATION
                        "ACCESS_COARSE_LOCATION" -> android.Manifest.permission.ACCESS_COARSE_LOCATION
                        "READ_CONTACTS" -> android.Manifest.permission.READ_CONTACTS
                        "READ_SMS" -> android.Manifest.permission.READ_SMS
                        "SEND_SMS" -> android.Manifest.permission.SEND_SMS
                        "CALL_PHONE" -> android.Manifest.permission.CALL_PHONE
                        "READ_CALL_LOG" -> android.Manifest.permission.READ_CALL_LOG
                        "READ_CALENDAR" -> android.Manifest.permission.READ_CALENDAR
                        "WRITE_CALENDAR" -> android.Manifest.permission.WRITE_CALENDAR
                        "BLUETOOTH_CONNECT" -> android.Manifest.permission.BLUETOOTH_CONNECT
                        "READ_MEDIA_IMAGES" -> android.Manifest.permission.READ_MEDIA_IMAGES
                        "READ_MEDIA_VIDEO" -> android.Manifest.permission.READ_MEDIA_VIDEO
                        "READ_MEDIA_AUDIO" -> android.Manifest.permission.READ_MEDIA_AUDIO
                        else -> "android.permission.$permission"
                    }
                    androidx.core.content.ContextCompat.checkSelfPermission(ctx, androidPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }
    }

    single { PluginManager(context = androidContext(), toolRegistry = get()) }

    single { FileLockManager() }

    single { ToolInitializer(context = androidContext(), toolRegistry = get(), tokenUsageManager = get(), accessibilityController = get(), phoneAutomator = get(), acpClientStore = get(), linuxManager = get(), fileLockManager = get(), skillLifecycleManager = get(), subAgentOrchestrator = get(), subAgentAggregator = get(), agentFactory = get(), cronJobManager = get(), configStorage = get(), skillManager = get(), inboxStore = get(), sessionStore = get(), agentRepository = get(), memoryRepository = get()) }

    single { SkillScriptExecutor(context = androidContext(), linuxManager = get()) }

    single {
        val context = androidContext()
        val skillsDir = java.io.File(context.filesDir, "skills")
        SkillManager(context = context, skillsDir = skillsDir)
    }

    single {
        SkillLifecycleManager(
            context = androidContext(),
            skillManager = get(),
            toolRegistry = get(),
            linuxManager = getOrNull(),
            modelProviderStore = getOrNull()
        )
    }
}
