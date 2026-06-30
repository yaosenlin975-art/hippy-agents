package com.lin.hippyagent.core.deeplink

/**
 * dumpsys 解析出的 Intent 规格。
 */
data class CapturedIntentSpec(
    val action: String?,
    val dataUri: String?,
    val component: String?,
    val flags: String?,
    val categories: List<String>,
    val extras: Map<String, String>
) {
    val hasPreciseJumpSpec: Boolean get() = !dataUri.isNullOrBlank() || extras.isNotEmpty()
}
