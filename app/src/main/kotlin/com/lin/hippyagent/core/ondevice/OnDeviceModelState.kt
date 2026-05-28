package com.lin.hippyagent.core.ondevice

import kotlinx.serialization.Serializable

@Serializable
data class OnDeviceModelState(
    val modelId: String,
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0,
    val localPath: String = "",
    val engineState: EngineState = EngineState.NOT_LOADED,
    val activeBackend: String = "",
    val peakMemoryMb: Int = 0,
)

@Serializable
enum class DownloadState {
    NOT_DOWNLOADED, DOWNLOADING, PAUSED, DOWNLOADED, DOWNLOAD_FAILED
}

@Serializable
enum class EngineState {
    NOT_LOADED, LOADING, LOADED, LOAD_FAILED, UNLOADING
}

enum class BackendPreference { AUTO, CPU, GPU, NPU }

data class DownloadProgress(
    val modelId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speed: Long,
    val state: DownloadState,
)
