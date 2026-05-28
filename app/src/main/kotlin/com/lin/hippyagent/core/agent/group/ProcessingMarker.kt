package com.lin.hippyagent.core.agent.group

import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
data class ProcessingMarkerData(
    val taskId: String,
    val processor: String,
    val startedAt: Long,
    val expiresAt: Long
)

class ProcessingMarker {

    fun createMarker(taskId: String, processor: String, timeoutMinutes: Int = 15): ProcessingMarkerData {
        val now = System.currentTimeMillis()
        val expiresAt = now + timeoutMinutes * 60_000L
        Timber.d("Marker created: $taskId by $processor, expires at $expiresAt")
        return ProcessingMarkerData(
            taskId = taskId,
            processor = processor,
            startedAt = now,
            expiresAt = expiresAt
        )
    }

    fun isExpired(marker: ProcessingMarkerData): Boolean {
        val expired = System.currentTimeMillis() > marker.expiresAt
        if (expired) {
            Timber.d("Marker expired: ${marker.taskId} by ${marker.processor}")
        }
        return expired
    }
}
