package com.lin.hippyagent.core.security

/**
 * 运行时安全配置。
 * 存储到 SharedPreferences（SettingsRepository）。
 *
 * @param securityEventAuditEnabled 依赖 traceEnabled（Direction A）；
 *        若 Trace 系统未开启，安全事件仅 Timber 日志，不入库。
 */
data class SecurityConfig(
    val piiMaskingEnabled: Boolean = true,
    val injectionDetectionEnabled: Boolean = true,
    val jailbreakDetectionEnabled: Boolean = true,
    val outputValidationEnabled: Boolean = true,
    val memoryContentGuardEnabled: Boolean = true,
    val securityEventAuditEnabled: Boolean = true,
    val piiMaskerMaxSize: Int = 1000,
    val maxConsecutiveValidationFailures: Int = 3
)
