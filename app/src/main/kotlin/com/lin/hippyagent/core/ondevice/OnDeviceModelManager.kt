package com.lin.hippyagent.core.ondevice

import android.app.ActivityManager
import android.content.Context
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelCapability
import com.lin.hippyagent.core.model.ModelConfig
import com.lin.hippyagent.core.model.ModelProvider
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.ModelStreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.use
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OnDeviceModelManager(
    private val context: Context,
    private val modelStore: OnDeviceModelStore,
    private val providerStore: ModelProviderStore,
    private val catalog: OnDeviceModelCatalog,
) {
    private val _currentEngineModelId = MutableStateFlow<String?>(null)
    val currentEngineModelId: StateFlow<String?> = _currentEngineModelId.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()

    private val downloadScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()
    private val pausedByUser = ConcurrentHashMap<String, Boolean>()

    private var currentEngine: LiteRTLMEngine? = null
    private val downloadClients = ConcurrentHashMap<String, OkHttpClient>()
    private val engineMutex = Mutex()
    private val customModels = ConcurrentHashMap<String, OnDeviceModelConfig>()

    companion object {
        private val SAFE_MODEL_ID_REGEX = Regex("[^a-zA-Z0-9_\\-]")
    }

    val availableModels: List<OnDeviceModelConfig>
        get() = catalog.getAvailableModels() + customModels.values.toList()

    fun getModelConfig(modelId: String): OnDeviceModelConfig? = catalog.getModel(modelId) ?: customModels[modelId]

    fun getEngineState(modelId: String): EngineState {
        if (_currentEngineModelId.value != modelId) return EngineState.NOT_LOADED
        return if (currentEngine?.isReady() == true) EngineState.LOADED else EngineState.NOT_LOADED
    }

    fun downloadModel(modelId: String): Flow<DownloadProgress> = flow {
        val config = catalog.getModel(modelId) ?: customModels[modelId]
            ?: throw IllegalArgumentException("Model not found: $modelId")
        val resolvedUrl = HuggingFaceMirror.resolveUrl(config.downloadUrl)
        val modelDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "ondevice_models")
        if (!modelDir.exists()) modelDir.mkdirs()
        val safeModelId = modelId.replace(SAFE_MODEL_ID_REGEX, "_")
        val targetFile = File(modelDir, "$safeModelId.litertlm")

        modelStore.addModel(OnDeviceModelState(modelId = modelId))
        modelStore.updateModel(modelId) { it.copy(downloadState = DownloadState.DOWNLOADING, downloadedBytes = 0) }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        downloadClients[modelId] = client

        try {
            val request = Request.Builder().url(resolvedUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                modelStore.updateModel(modelId) { it.copy(downloadState = DownloadState.DOWNLOAD_FAILED) }
                throw RuntimeException("Download failed: ${response.code}")
            }

            val body = response.body ?: throw RuntimeException("Empty response body")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            var lastSpeedCalcTime = System.nanoTime()
            var lastSpeedCalcBytes = 0L
            var speed = 0L

            targetFile.sink().buffer().use { sink ->
                body.source().use { source ->
                    val buffer = okio.Buffer()
                    while (!source.exhausted()) {
                        val read = source.read(buffer, 8192)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        downloadedBytes += read
                        val now = System.nanoTime()
                        val elapsed = now - lastSpeedCalcTime
                        if (elapsed >= 1_000_000_000L) {
                            speed = (downloadedBytes - lastSpeedCalcBytes) * 1_000_000_000L / elapsed
                            lastSpeedCalcTime = now
                            lastSpeedCalcBytes = downloadedBytes
                        }
                        val progress = DownloadProgress(modelId, downloadedBytes, totalBytes, speed, DownloadState.DOWNLOADING)
                        emit(progress)
                        _downloadProgress.update { it + (modelId to progress) }
                    }
                }
            }

            modelStore.updateModel(modelId) {
                it.copy(
                    downloadState = DownloadState.DOWNLOADED,
                    downloadedBytes = downloadedBytes,
                    localPath = targetFile.absolutePath
                )
            }
            val finalProgress = DownloadProgress(modelId, downloadedBytes, totalBytes, speed, DownloadState.DOWNLOADED)
            emit(finalProgress)
            _downloadProgress.update { it + (modelId to finalProgress) }
            syncVirtualProviders()
        } catch (e: Exception) {
            Timber.e(e, "Download failed for $modelId")
            if (pausedByUser.remove(modelId) == true) {
                modelStore.updateModel(modelId) { it.copy(downloadState = DownloadState.PAUSED) }
            } else {
                modelStore.updateModel(modelId) { it.copy(downloadState = DownloadState.DOWNLOAD_FAILED) }
                targetFile.delete()
            }
            throw e
        } finally {
            downloadClients.remove(modelId)
            activeDownloads.remove(modelId)
            _downloadProgress.update { it - modelId }
        }
    }.flowOn(Dispatchers.IO)

    fun startDownload(modelId: String) {
        if (activeDownloads.putIfAbsent(modelId, true) != null) return
        pausedByUser.remove(modelId)
        downloadScope.launch {
            runCatching { downloadModel(modelId).collect { } }
                .onFailure { Timber.e(it, "Background download failed for $modelId") }
        }
    }

    fun startDownloadWithConfig(config: OnDeviceModelConfig) {
        customModels[config.id] = config
        startDownload(config.id)
    }

    suspend fun recoverStaleDownloads() {
        val states = modelStore.models.first()
        for (state in states) {
            if (state.downloadState == DownloadState.DOWNLOADING && !activeDownloads.containsKey(state.modelId)) {
                modelStore.updateModel(state.modelId) { it.copy(downloadState = DownloadState.PAUSED) }
            }
        }
    }

    fun isDownloading(modelId: String): Boolean = activeDownloads.containsKey(modelId)

    fun pauseDownload(modelId: String) {
        pausedByUser[modelId] = true
        downloadClients.remove(modelId)?.dispatcher?.cancelAll()
        downloadScope.launch {
            modelStore.updateModel(modelId) { it.copy(downloadState = DownloadState.PAUSED) }
        }
    }

    fun resumeDownload(modelId: String): Flow<DownloadProgress> = downloadModel(modelId)

    suspend fun deleteModel(modelId: String) {
        if (_currentEngineModelId.value == modelId) {
            unloadEngine()
        }
        modelStore.updateModel(modelId) { state ->
            if (state.localPath.isNotBlank()) {
                File(state.localPath).delete()
            }
            OnDeviceModelState(modelId = modelId)
        }
        modelStore.removeModel(modelId)
        customModels.remove(modelId)
        syncVirtualProviders()
    }

    suspend fun loadEngine(modelId: String, backend: BackendPreference = BackendPreference.AUTO) = engineMutex.withLock {
        if (_currentEngineModelId.value == modelId && currentEngine?.isReady() == true) return@withLock
        if (_currentEngineModelId.value != null) {
            doUnloadEngine()
        }
        val states = modelStore.models.first()
        val state = states.firstOrNull { it.modelId == modelId }
            ?: throw IllegalArgumentException("Model state not found: $modelId")
        if (state.downloadState != DownloadState.DOWNLOADED) {
            throw IllegalStateException("Model not downloaded: $modelId")
        }
        val config = catalog.getModel(modelId) ?: customModels[modelId]
            ?: throw IllegalArgumentException("Model config not found: $modelId")
        val memInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024 * 1024)
        if (availMb < config.minRamMb) {
            throw IllegalStateException("可用内存不足: ${availMb}MB < 需要${config.minRamMb}MB")
        }

        modelStore.updateModel(modelId) { it.copy(engineState = EngineState.LOADING) }
        try {
            val engine = LiteRTLMEngine(
                modelPath = state.localPath,
                backendPref = backend,
                cacheDir = context.cacheDir.absolutePath,
                context = context,
                capabilities = config.capabilities,
            )
            engine.initialize()
            currentEngine = engine
            _currentEngineModelId.value = modelId
            modelStore.updateModel(modelId) {
                it.copy(
                    engineState = EngineState.LOADED,
                    activeBackend = backend.name
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Engine load failed for $modelId")
            modelStore.updateModel(modelId) { it.copy(engineState = EngineState.LOAD_FAILED) }
            throw e
        }
    }

    suspend fun unloadEngine() = engineMutex.withLock {
        doUnloadEngine()
    }

    private suspend fun doUnloadEngine() {
        val modelId = _currentEngineModelId.value ?: return
        modelStore.updateModel(modelId) { it.copy(engineState = EngineState.UNLOADING) }
        runCatching { currentEngine?.close() }
        currentEngine = null
        _currentEngineModelId.value = null
        modelStore.updateModel(modelId) { it.copy(engineState = EngineState.NOT_LOADED, activeBackend = "") }
    }

    suspend fun generate(modelId: String, request: ModelCallRequest): ModelCallResponse = engineMutex.withLock {
        val engine = currentEngine
            ?: throw IllegalStateException("No engine loaded")
        if (_currentEngineModelId.value != modelId) {
            throw IllegalStateException("Engine loaded for ${_currentEngineModelId.value}, not $modelId")
        }
        engine.generate(request)
    }

    fun generateStream(modelId: String, request: ModelCallRequest): Flow<ModelStreamChunk> {
        val engineRef = currentEngine
            ?: throw IllegalStateException("No engine loaded")
        if (_currentEngineModelId.value != modelId) {
            throw IllegalStateException("Engine loaded for ${_currentEngineModelId.value}, not $modelId")
        }
        return engineRef.generateStream(request)
    }

    suspend fun syncVirtualProviders() {
        val states = modelStore.models.first()
        val downloadedIds = states.filter { it.downloadState == DownloadState.DOWNLOADED }.map { it.modelId }.toSet()
        val providers = providerStore.providers.first()
        val existingVirtualIds = providers.filter { it.isVirtual }.map { it.id }.toSet()

        for (modelId in downloadedIds) {
            val virtualId = "ondevice-$modelId"
            if (virtualId !in existingVirtualIds) {
                val config = catalog.getModel(modelId) ?: customModels[modelId] ?: continue
                providerStore.addProvider(buildVirtualProvider(config))
            }
        }

        for (virtualId in existingVirtualIds) {
            val modelId = virtualId.removePrefix("ondevice-")
            if (modelId !in downloadedIds) {
                providerStore.deleteProvider(virtualId)
            }
        }
    }

    private fun buildVirtualProvider(config: OnDeviceModelConfig): ModelProvider {
        return ModelProvider(
            id = "ondevice-${config.id}",
            name = "${config.name} (端侧)",
            baseUrl = "litertlm://${config.id}",
            apiKey = "",
            protocol = "litertlm",
            enabled = true,
            isDefault = false,
            isVirtual = true,
            models = listOf(ModelConfig(
                id = config.id,
                name = config.id,
                displayName = config.name,
                capabilities = mapCapabilities(config),
                contextWindow = config.contextWindow,
                maxTokens = config.maxTokens,
                free = true,
            ))
        )
    }

    private fun mapCapabilities(config: OnDeviceModelConfig): Set<ModelCapability> {
        val caps = mutableSetOf(ModelCapability.STREAMING)
        if (config.capabilities.contains(OnDeviceCapability.VISION)) caps.add(ModelCapability.VISION)
        if (config.capabilities.contains(OnDeviceCapability.AUDIO)) caps.add(ModelCapability.AUDIO)
        if (config.capabilities.contains(OnDeviceCapability.TOOL_CALL)) caps.add(ModelCapability.TOOL_CALL)
        if (config.capabilities.contains(OnDeviceCapability.THINKING)) caps.add(ModelCapability.REASONING)
        return caps
    }

    suspend fun transcribeAudio(pcmBytes: ByteArray): String {
        val modelId = _currentEngineModelId.value
            ?: throw IllegalStateException("无当前引擎模型")
        val config = catalog.getModel(modelId)
        if (config == null || OnDeviceCapability.AUDIO !in config.capabilities) {
            throw IllegalStateException("当前模型不支持音频输入")
        }
        return engineMutex.withLock {
            withContext(Dispatchers.Default) {
                val eng = currentEngine
                    ?: throw IllegalStateException("引擎未加载")
                val conv = eng.createConversation(
                    com.google.ai.edge.litertlm.ConversationConfig(
                        systemInstruction = com.google.ai.edge.litertlm.Contents.of(
                            com.google.ai.edge.litertlm.Content.Text("You are a speech-to-text transcriber. Transcribe the following audio accurately. Output only the transcription text, no extra commentary.")
                        ),
                        samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
                    )
                )
                try {
                    val contents = com.google.ai.edge.litertlm.Contents.of(listOf(
                        com.google.ai.edge.litertlm.Content.AudioBytes(pcmBytes),
                        com.google.ai.edge.litertlm.Content.Text("Transcribe this audio:"),
                    ))
                    val response = conv.sendMessage(contents)
                    response.toString().trim()
                } finally {
                    conv.close()
                }
            }
        }
    }

}
