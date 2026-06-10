package com.lin.hippyagent.core.scheduler

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelClientFactory
import com.lin.hippyagent.core.model.ModelProvider
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.storage.SecureStorage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

enum class ScheduleParsePhase { IDLE, PARSING, SUCCESS, ERROR }

@Immutable
data class ScheduleCreateUiState(
    val nlText: String = "",
    val phase: ScheduleParsePhase = ScheduleParsePhase.IDLE,
    val parseResult: ScheduleParseResult? = null,
    val selectedCandidateIndex: Int = 0,
    val manualCron: String = "",
    val manualMode: Boolean = false,
    val taskName: String = "",
    val silentMode: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null
) {
    val canSave: Boolean
        get() = !saving && (parseResult?.success == true || (manualMode && manualCron.isNotBlank())) && taskName.isNotBlank()
}

class ScheduleCreateViewModel(
    private val agentId: String,
    private val modelProviderStore: ModelProviderStore,
    private val secureStorage: SecureStorage,
    private val scheduledTaskStore: ScheduledTaskStore,
    private val onDeviceModelManager: com.lin.hippyagent.core.ondevice.OnDeviceModelManager? = null,
    private val sessionId: String = "",
    private val cronService: com.lin.hippyagent.core.cron.CronService? = null
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleCreateUiState())
    val state: StateFlow<ScheduleCreateUiState> = _state.asStateFlow()

    private val ruleParser: NaturalLanguageTimeParser = NaturalLanguageTimeParser()
    private val clientMutex = Mutex()
    @Volatile private var cachedClient: ModelClient? = null
    @Volatile private var cachedModelName: String = DEFAULT_MODEL_NAME

    private var parseJob: Job? = null

    fun onNlTextChange(text: String) {
        _state.value = _state.value.copy(
            nlText = text,
            phase = if (text.isBlank()) ScheduleParsePhase.IDLE else ScheduleParsePhase.PARSING,
            parseResult = null,
            errorMessage = null,
            selectedCandidateIndex = 0,
            manualMode = false
        )
        parseJob?.cancel()
        if (text.isBlank()) return
        parseJob = viewModelScope.launch {
            delay(PARSE_DEBOUNCE_MS)
            runParse(text.trim())
        }
    }

    fun onTaskNameChange(name: String) {
        _state.value = _state.value.copy(taskName = name)
    }

    fun onManualCronChange(cron: String) {
        _state.value = _state.value.copy(manualCron = cron)
    }

    fun toggleManualMode(enabled: Boolean) {
        _state.value = _state.value.copy(manualMode = enabled)
    }

    fun onSilentModeChange(enabled: Boolean) {
        _state.value = _state.value.copy(silentMode = enabled)
    }

    fun selectCandidate(index: Int) {
        val current = _state.value
        val candidates = current.parseResult?.ambiguityCandidates ?: return
        if (index !in candidates.indices) return
        val chosen = candidates[index]
        _state.value = current.copy(
            selectedCandidateIndex = index,
            parseResult = current.parseResult.copy(
                cron = chosen.cron,
                isoTimestamp = chosen.isoTimestamp,
                humanReadable = chosen.humanReadable,
                nextFireTime = chosen.nextFireTime
            )
        )
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.value = current.copy(saving = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                val cron = if (current.manualMode) current.manualCron.trim() else current.parseResult?.cron.orEmpty()
                val iso = if (current.manualMode) "" else current.parseResult?.isoTimestamp.orEmpty()
                val nextMs = if (current.manualMode) 0L else current.parseResult?.nextFireTime ?: 0L
                val method = if (current.manualMode) ParseMethod.FALLBACK else current.parseResult?.parseMethod ?: ParseMethod.LLM
                val name = if (current.taskName.isBlank()) current.nlText.take(20) else current.taskName
                val task = ScheduledTask(
                    name = name,
                    query = current.nlText,
                    cron = cron,
                    isoTimestamp = iso,
                    originalNlText = current.nlText,
                    parseMethod = method.name,
                    isOneShot = current.parseResult?.isOneShot == true,
                    nextFireTime = nextMs,
                    agentId = agentId,
                    sessionId = sessionId,
                    silentMode = current.silentMode
                )
                scheduledTaskStore.insert(task)
                cronService?.scheduleTask(
                    com.lin.hippyagent.core.cron.CronTask(
                        id = task.id,
                        name = task.name,
                        cronExpression = task.cron,
                        taskRef = task.query,
                        enabled = task.enabled,
                        nextFireTime = nextMs
                    )
                )
            }.onSuccess {
                _state.value = _state.value.copy(saving = false, saved = true)
                Timber.i("ScheduleCreateViewModel: saved task")
            }.onFailure { e ->
                Timber.e(e, "ScheduleCreateViewModel: save failed")
                _state.value = _state.value.copy(saving = false, errorMessage = "保存失败: ${e.message?.take(120)}")
            }
        }
    }

    fun consumeSaved() {
        _state.value = _state.value.copy(saved = false)
    }

    private suspend fun runParse(text: String) {
        _state.value = _state.value.copy(phase = ScheduleParsePhase.PARSING, errorMessage = null)
        val llmResult = parseWithLlm(text)
        if (llmResult != null && llmResult.success) {
            _state.value = _state.value.copy(
                phase = ScheduleParsePhase.SUCCESS,
                parseResult = llmResult,
                selectedCandidateIndex = 0
            )
            return
        }
        val ruleResult = ruleParser.parse(text)
        if (ruleResult.success) {
            _state.value = _state.value.copy(
                phase = ScheduleParsePhase.SUCCESS,
                parseResult = ruleResult.copy(parseMethod = ParseMethod.RULE),
                selectedCandidateIndex = 0
            )
            return
        }
        val combinedError = buildString {
            append(llmResult?.errorMessage ?: "LLM 未配置或不可用")
            append("\n")
            append(ruleResult.errorMessage ?: "规则解析失败")
        }
        _state.value = _state.value.copy(
            phase = ScheduleParsePhase.ERROR,
            parseResult = ScheduleParseResult(
                success = false,
                errorMessage = combinedError,
                parseMethod = ParseMethod.FALLBACK,
                rawText = text
            ),
            errorMessage = combinedError
        )
    }

    private suspend fun parseWithLlm(text: String): ScheduleParseResult? {
        val client = resolveClient() ?: return null
        val parser = NaturalLanguageScheduleParser(client, cachedModelName)
        return parser.parse(text)
    }

    private suspend fun resolveClient(): ModelClient? = clientMutex.withLock {
        cachedClient?.let { return it }
        val providers = runCatching { modelProviderStore.providers.first() }.getOrNull()
        val provider = pickDefaultProvider(providers) ?: return null
        val modelName = pickDefaultModel(provider)
        val client = runCatching {
            ModelClientFactory.create(
                provider = provider,
                secureStorage = secureStorage,
                onDeviceModelManager = onDeviceModelManager
            )
        }.getOrNull() ?: return null
        cachedModelName = modelName
        cachedClient = client
        client
    }

    private fun pickDefaultProvider(providers: List<ModelProvider>?): ModelProvider? {
        if (providers.isNullOrEmpty()) return null
        return providers.firstOrNull { it.isDefault }
            ?: providers.firstOrNull { it.enabled }
    }

    private fun pickDefaultModel(provider: ModelProvider): String {
        val enabled = provider.models.filter { it.enabled }
        val chosen = enabled.firstOrNull { it.isDefault }
            ?: enabled.firstOrNull()
            ?: provider.models.firstOrNull()
        return chosen?.name ?: DEFAULT_MODEL_NAME
    }

    companion object {
        const val PARSE_DEBOUNCE_MS = 300L
        private const val DEFAULT_MODEL_NAME = "gpt-4o-mini"
    }
}
