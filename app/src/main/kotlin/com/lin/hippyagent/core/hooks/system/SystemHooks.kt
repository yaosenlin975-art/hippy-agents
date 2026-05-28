package com.lin.hippyagent.core.hooks.system

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import timber.log.Timber

// ============================================================
// SMS 事件 Hook
// ============================================================

class SmsEventHook : SystemHook {

    override val name = "sms"
    override val description = "监听短信到达"
    override val eventTypes = listOf(SystemEventType.SMS_RECEIVED)

    private var receiver: BroadcastReceiver? = null

    override fun register(context: Context, callback: (SystemEvent) -> Unit) {
        if (!isEnabled(context)) return
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
                for (msg in messages) {
                    callback(SystemEvent(
                        type = SystemEventType.SMS_RECEIVED,
                        sourceHook = "sms",
                        payload = mapOf(
                            "sender" to (msg.displayOriginatingAddress ?: "unknown"),
                            "body" to (msg.messageBody?.take(500) ?: ""),
                            "timestamp" to msg.timestampMillis.toString()
                        )
                    ))
                }
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun unregister(context: Context) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    override fun isEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        else true
    }
}

// ============================================================
// 来电事件 Hook
// ============================================================

class CallEventHook : SystemHook {

    override val name = "call"
    override val description = "监听来电状态"
    override val eventTypes = listOf(SystemEventType.INCOMING_CALL, SystemEventType.CALL_ENDED)

    private var receiver: BroadcastReceiver? = null

    override fun register(context: Context, callback: (SystemEvent) -> Unit) {
        if (!isEnabled(context)) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "unknown"
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING ->
                        callback(SystemEvent(SystemEventType.INCOMING_CALL, "call",
                            payload = mapOf("number" to number)))
                    TelephonyManager.EXTRA_STATE_IDLE ->
                        callback(SystemEvent(SystemEventType.CALL_ENDED, "call",
                            payload = mapOf("number" to number)))
                }
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun unregister(context: Context) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    override fun isEnabled(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

// ============================================================
// 通知事件 Hook
// ============================================================

class NotificationEventHook : SystemHook {

    override val name = "notification"
    override val description = "监听系统通知（轻量版，通过 BroadcastReceiver）"
    override val eventTypes = listOf(SystemEventType.NOTIFICATION_POSTED)

    private var receiver: BroadcastReceiver? = null

    override fun register(context: Context, callback: (SystemEvent) -> Unit) {
        // 轻量实现：监听应用安装变化（完整通知监听需 NotificationListenerService）
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                val pkg = intent.data?.encodedSchemeSpecificPart ?: return
                val type = when (action) {
                    Intent.ACTION_PACKAGE_ADDED -> SystemEventType.APP_INSTALLED
                    Intent.ACTION_PACKAGE_REMOVED -> SystemEventType.APP_UNINSTALLED
                    else -> return
                }
                callback(SystemEvent(type, "notification",
                    payload = mapOf("package" to pkg)))
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun unregister(context: Context) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}

// ============================================================
// 电量事件 Hook
// ============================================================

class BatteryEventHook : SystemHook {

    override val name = "battery"
    override val description = "监听电量变化"
    override val eventTypes = listOf(SystemEventType.BATTERY_LOW, SystemEventType.BATTERY_CHARGING)

    private var receiver: BroadcastReceiver? = null

    override fun register(context: Context, callback: (SystemEvent) -> Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_LOW ->
                        callback(SystemEvent(SystemEventType.BATTERY_LOW, "battery",
                            payload = mapOf("level" to "15")))
                    Intent.ACTION_POWER_CONNECTED ->
                        callback(SystemEvent(SystemEventType.BATTERY_CHARGING, "battery",
                            payload = mapOf("charging" to "true")))
                    Intent.ACTION_POWER_DISCONNECTED ->
                        callback(SystemEvent(SystemEventType.BATTERY_CHARGING, "battery",
                            payload = mapOf("charging" to "false")))
                }
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun unregister(context: Context) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}

// ============================================================
// 屏幕事件 Hook
// ============================================================

class ScreenEventHook : SystemHook {

    override val name = "screen"
    override val description = "监听屏幕亮灭"
    override val eventTypes = listOf(SystemEventType.SCREEN_ON, SystemEventType.SCREEN_OFF)

    private var receiver: BroadcastReceiver? = null

    override fun register(context: Context, callback: (SystemEvent) -> Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val type = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> SystemEventType.SCREEN_ON
                    Intent.ACTION_SCREEN_OFF -> SystemEventType.SCREEN_OFF
                    else -> return
                }
                callback(SystemEvent(type, "screen"))
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun unregister(context: Context) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}

// ============================================================
// APP 安装/卸载事件 Hook
// ============================================================

class AppInstallEventHook : SystemHook {

    override val name = "app_install"
    override val description = "监听 APP 安装/卸载"
    override val eventTypes = listOf(SystemEventType.APP_INSTALLED, SystemEventType.APP_UNINSTALLED)

    private var receiver: BroadcastReceiver? = null

    override fun register(context: Context, callback: (SystemEvent) -> Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.encodedSchemeSpecificPart ?: return
                val type = when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED ->
                        SystemEventType.APP_INSTALLED
                    Intent.ACTION_PACKAGE_REMOVED -> SystemEventType.APP_UNINSTALLED
                    else -> return
                }
                callback(SystemEvent(type, "app_install",
                    payload = mapOf("package" to pkg)))
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun unregister(context: Context) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}

// ============================================================
// 剪贴板事件 Hook
// ============================================================

class ClipboardEventHook : SystemHook {

    override val name = "clipboard"
    override val description = "监听剪贴板变化"
    override val eventTypes = listOf(SystemEventType.CLIPBOARD_CHANGED)

    private var listener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun register(context: Context, callback: (SystemEvent) -> Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.take(200) ?: return@OnPrimaryClipChangedListener
            callback(SystemEvent(SystemEventType.CLIPBOARD_CHANGED, "clipboard",
                payload = mapOf("text" to text)))
        }
        clipboard.addPrimaryClipChangedListener(listener)
    }

    override fun unregister(context: Context) {
        listener?.let {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            clipboard.removePrimaryClipChangedListener(it)
        }
        listener = null
    }
}
