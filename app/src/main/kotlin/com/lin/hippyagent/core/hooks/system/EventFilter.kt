package com.lin.hippyagent.core.hooks.system

import timber.log.Timber

/**
 * 事件过滤器 — 去重/黑白名单/静默时间/限流
 *
 * 参考: OpenClaw hooks/interfaces.ts
 */
class EventFilter {
    // 静态去重缓存（key → lastTimestamp）
    private val filterRules = mutableListOf<FilterRule>()
    // 静默时间配置
    private var silentStartMinute: Int = 23 * 60   // 23:00
    private var silentEndMinute: Int = 7 * 60      // 07:00
    private var silentEnabled: Boolean = false
    // 每个事件类型的最短间隔 (ms)
    private val minIntervals = mutableMapOf<SystemEventType, Long>()

    init {
        // 默认最小间隔
        SystemEventType.values().forEach { type ->
            minIntervals[type] = type.minIntervalMs
        }
    }

    /** 添加过滤规则 */
    fun addRule(rule: FilterRule) {
        filterRules.add(rule)
    }

    /** 设置静默时间 */
    fun setSilentHours(enabled: Boolean, startMinute: Int = 23 * 60, endMinute: Int = 7 * 60) {
        silentEnabled = enabled
        silentStartMinute = startMinute
        silentEndMinute = endMinute
    }

    /** 设置事件最小间隔 */
    fun setMinInterval(type: SystemEventType, intervalMs: Long) {
        minIntervals[type] = intervalMs
    }

    /** 判断事件是否应该被处理 */
    fun shouldProcess(event: SystemEvent): Boolean {
        // 1. 静默时间检查
        if (silentEnabled && isInSilentHours()) {
            // 来电和闹钟类事件仍允许
            if (event.type != SystemEventType.INCOMING_CALL) {
                return false
            }
        }

        // 2. 自定义规则检查
        for (rule in filterRules) {
            when (rule) {
                is FilterRule.BlockPackage -> {
                    val pkg = event.payload["package"]?.toString() ?: ""
                    if (pkg.contains(rule.packagePrefix, ignoreCase = true)) return false
                }
                is FilterRule.BlockSender -> {
                    val sender = event.payload["sender"]?.toString() ?: ""
                    if (sender.contains(rule.senderPrefix, ignoreCase = true)) return false
                }
                is FilterRule.AllowOnly -> {
                    val value = event.payload[rule.key]?.toString() ?: ""
                    if (rule.allowedValues.none { value.contains(it, ignoreCase = true) }) {
                        return false
                    }
                }
            }
        }

        return true
    }

    fun getSilentConfig(): Triple<Boolean, Int, Int> = Triple(silentEnabled, silentStartMinute, silentEndMinute)

    private fun isInSilentHours(): Boolean {
        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                now.get(java.util.Calendar.MINUTE)

        return if (silentStartMinute <= silentEndMinute) {
            // 同一天内 (如 02:00-06:00)
            currentMinutes in silentStartMinute..silentEndMinute
        } else {
            // 跨天 (如 23:00-07:00)
            currentMinutes >= silentStartMinute || currentMinutes <= silentEndMinute
        }
    }
}

/** 过滤规则 */
sealed class FilterRule {
    /** 屏蔽特定包名的通知 */
    data class BlockPackage(val packagePrefix: String) : FilterRule()
    /** 屏蔽特定发件人的短信 */
    data class BlockSender(val senderPrefix: String) : FilterRule()
    /** 仅允许特定值的字段 */
    data class AllowOnly(val key: String, val allowedValues: List<String>) : FilterRule()
}
