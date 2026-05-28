package com.lin.hippyagent.core.hooks.system

import android.content.Context
import com.lin.hippyagent.core.hooks.HippyEvent
import com.lin.hippyagent.core.hooks.EventMeta
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 系统事件类型
 */
enum class SystemEventType(
    val description: String,
    val minIntervalMs: Long = 0L
) {
    SMS_RECEIVED("短信到达", 30_000),
    INCOMING_CALL("来电", 0),
    CALL_ENDED("通话结束", 0),
    NOTIFICATION_POSTED("通知到达", 1_000),
    NOTIFICATION_REMOVED("通知移除", 0),
    BATTERY_LOW("电量低", 600_000),
    BATTERY_CHARGING("充电中", 600_000),
    CALENDAR_EVENT("日历事件", 300_000),
    APP_INSTALLED("APP 安装", 10_000),
    APP_UNINSTALLED("APP 卸载", 10_000),
    SCREEN_ON("屏幕亮", 0),
    SCREEN_OFF("屏幕灭", 0),
    CLIPBOARD_CHANGED("剪贴板变化", 5_000),
    BOOT_COMPLETED("系统启动", 0)
}

/**
 * 系统事件
 */
data class SystemEvent(
    val type: SystemEventType,
    val sourceHook: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, Any> = emptyMap()
) {
    /** 用于去重的摘要键 */
    fun dedupKey(): String = payload.entries
        .sortedBy { it.key }
        .take(3)
        .joinToString("|") { "${it.key}=${it.value}" }
}

/**
 * 系统事件监听器接口 — 每个系统 Hook 实现此接口
 */
interface SystemHook {
    val name: String
    val description: String
    val eventTypes: List<SystemEventType>

    /** 注册监听（BroadcastReceiver 等） */
    fun register(context: Context, callback: (SystemEvent) -> Unit)

    /** 取消注册 */
    fun unregister(context: Context)

    /** 是否已启用 */
    fun isEnabled(context: Context): Boolean = true
}

/**
 * 系统事件回调 — 系统事件触发时由各个 Hook 调用
 */
fun interface SystemEventCallback {
    fun onEvent(event: SystemEvent)
}

/**
 * 系统事件管理器 — 管理所有系统事件 Hook 的注册、过滤、调度
 *
 * 参考: OpenClaw hooks/HookManager.ts
 */
class SystemHookManager(
    private val context: Context,
    private val eventCallback: SystemEventCallback
) {
    private val hooks = mutableListOf<SystemHook>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dedupCache = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 200  // 上限 200 条，超限自动淘汰最旧条目
        }
    }

    private var _eventFilter: EventFilter? = null
    val eventFilter: EventFilter get() {
        if (_eventFilter == null) _eventFilter = EventFilter()
        return _eventFilter!!
    }

    /** 注册 Hook */
    fun register(hook: SystemHook) {
        hooks.add(hook)
        if (hook.isEnabled(context)) {
            hook.register(context) { event ->
                onSystemEvent(event)
            }
            Timber.d("SystemHook registered: ${hook.name}")
        }
    }

    /** 获取所有已注册的 Hook */
    fun getRegisteredHooks(): List<SystemHook> = hooks.toList()

    /** 初始化所有系统 Hook */
    fun initialize() {
        register(SmsEventHook())
        register(CallEventHook())
        register(NotificationEventHook())
        register(BatteryEventHook())
        register(ScreenEventHook())
        register(AppInstallEventHook())
        register(ClipboardEventHook())
        Timber.i("SystemHookManager initialized with ${hooks.size} hooks")
    }

    /** 取消所有 Hook */
    fun shutdown() {
        hooks.forEach { it.unregister(context) }
        hooks.clear()
        dedupCache.clear()
        Timber.i("SystemHookManager shut down")
    }

    /** 系统事件处理 */
    private fun onSystemEvent(event: SystemEvent) {
        // 1. 去重
        val dedupKey = "${event.type.name}:${event.dedupKey()}"
        val now = System.currentTimeMillis()
        val lastTime = dedupCache[dedupKey]
        if (lastTime != null && now - lastTime < event.type.minIntervalMs) {
            return
        }
        dedupCache[dedupKey] = now

        // 2. 过滤
        if (!eventFilter.shouldProcess(event)) return

        // 3. 发布到 HippyHookManager 的事件流
        val hippyEvent = HippyEvent(
            type = "system:${event.type.name}",
            meta = EventMeta(
                agentId = "",
                turnId = "",
                sessionId = "",
                iteration = 0,
                source = "system_hook:${event.sourceHook}",
                tracePath = ""
            ),
            payload = event.payload
        )

        // 4. 通知回调（由 App 层处理具体路由）
        scope.launch {
            try {
                eventCallback.onEvent(event)
            } catch (e: Exception) {
                Timber.e(e, "SystemHook event callback failed for ${event.type}")
            }
        }
    }

    /** 手动触发事件（用于测试） */
    fun emitTestEvent(type: SystemEventType, payload: Map<String, Any> = emptyMap()) {
        onSystemEvent(SystemEvent(
            type = type,
            sourceHook = "test",
            payload = payload
        ))
    }
}
