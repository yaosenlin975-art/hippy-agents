package com.lin.hippyagent.core.deeplink.model

import kotlinx.serialization.Serializable

@Serializable
data class DeeplinkBookmark(
    val id: Long,
    val packageName: String,
    val appName: String,
    val pageTitle: String,
    val activityClassName: String,
    val capturedAt: Long,
    val action: String?,
    val dataUri: String?,
    val component: String?,
    val extras: Map<String, String> = emptyMap(),
    val intentCommand: String? = null
) {
    val hasPreciseJumpSpec: Boolean
        get() = !dataUri.isNullOrBlank() || extras.isNotEmpty()

    val effectiveAmCommand: String?
        get() = intentCommand ?: run {
            if (action == null && dataUri == null && component == null) null
            else buildString {
                append("am start")
                action?.let { append(" -a $it") }
                dataUri?.let { append(" -d $it") }
                component?.let { append(" -n $it") }
            }
        }
}
