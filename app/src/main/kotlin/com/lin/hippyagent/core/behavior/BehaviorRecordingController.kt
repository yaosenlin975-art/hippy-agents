package com.lin.hippyagent.core.behavior

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Immutable
import com.lin.hippyagent.core.accessibility.PhoneControlAccessibilityService
import com.lin.hippyagent.core.deeplink.DeeplinkBookmarkSession
import com.lin.hippyagent.core.privilege.SystemApiBridge
import com.lin.hippyagent.core.skill.SkillManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class RecordingUiState(
    val state: RecordingState = RecordingState.IDLE,
    val eventCount: Int = 0,
    val currentPageTitle: String? = null,
    val latestMessage: String? = null
)

enum class RecordingState { IDLE, RECORDING, FINALIZING }

class BehaviorRecordingController(
    private val application: Application,
    private val bridge: SystemApiBridge,
    private val bookmarkSession: DeeplinkBookmarkSession,
    private val skillManager: SkillManager
) {
    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(): Boolean {
        if (_uiState.value.state != RecordingState.IDLE) return false
        if (PhoneControlAccessibilityService.instance == null) {
            Timber.w("BehaviorRecordingController: accessibility service not running")
            return false
        }
        _uiState.value = RecordingUiState(state = RecordingState.RECORDING)
        BehaviorRecorder.start(application)
        bookmarkSession.start(application)
        Timber.i("BehaviorRecordingController: started")
        return true
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (_uiState.value.state != RecordingState.RECORDING) return
        BehaviorRecorder.onAccessibilityEvent(event)
        bookmarkSession.onAccessibilityEvent(event)
        _uiState.value = _uiState.value.copy(
            eventCount = BehaviorRecorder.uiState.value.eventCount,
            currentPageTitle = BehaviorRecorder.uiState.value.currentPageTitle
        )
    }

    fun bookmarkCurrentPage() {
        if (_uiState.value.state != RecordingState.RECORDING) return
        controllerScope.launch(Dispatchers.IO) {
            val message = bookmarkSession.bookmarkCurrentPage(application)
            _uiState.value = _uiState.value.copy(latestMessage = message)
        }
    }

    fun stop(): List<BehaviorRecorder.RecordedEvent> {
        if (_uiState.value.state != RecordingState.RECORDING) return emptyList()
        _uiState.value = _uiState.value.copy(state = RecordingState.FINALIZING)
        bookmarkSession.stop()
        val snapshot = BehaviorRecorder.stop()
        if (snapshot.isNotEmpty()) {
            val appAlias = snapshot.first().packageName.substringAfterLast(".").ifBlank { "App" }
            controllerScope.launch(Dispatchers.IO) {
                runCatching { BehaviorSkillWriter.writeSkill(snapshot, appAlias, skillManager) }
                    .onFailure { Timber.e(it, "writeSkill failed") }
            }
        }
        _uiState.value = RecordingUiState()
        return snapshot
    }
}
