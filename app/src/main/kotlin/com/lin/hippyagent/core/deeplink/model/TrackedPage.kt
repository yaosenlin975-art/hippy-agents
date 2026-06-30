package com.lin.hippyagent.core.deeplink.model

import kotlinx.serialization.Serializable

@Serializable
data class TrackedPage(
    val packageName: String,
    val activityClassName: String,
    val windowTitle: String,
    val appName: String
) {
    val displayTitle: String get() = windowTitle.ifBlank { activityClassName.substringAfterLast(".") }
}
