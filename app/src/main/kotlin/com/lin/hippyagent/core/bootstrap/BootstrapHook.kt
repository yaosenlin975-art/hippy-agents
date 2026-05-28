package com.lin.hippyagent.core.bootstrap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File

enum class BootstrapPhase {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    SKIPPED
}

data class BootstrapState(
    val phase: BootstrapPhase = BootstrapPhase.NOT_STARTED,
    val currentStep: Int = 0,
    val totalSteps: Int = 4,
    val isCompleted: Boolean = false
)

class BootstrapHook(
    private val workingDir: File
) {
    private val _state = MutableStateFlow(BootstrapState())
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    private val bootstrapFile = File(workingDir, "BOOTSTRAP.md")
    private val completedMarker = File(workingDir, ".bootstrap_completed")

    fun isBootstrapMode(): Boolean {
        return bootstrapFile.exists() && !completedMarker.exists()
    }

    fun getBootstrapContent(): String? {
        if (!isBootstrapMode()) return null
        return runCatching { bootstrapFile.readText() }.getOrNull()
    }

    fun markStepCompleted() {
        val currentState = _state.value
        val nextStep = currentState.currentStep + 1
        _state.value = currentState.copy(
            currentStep = nextStep,
            phase = if (nextStep >= currentState.totalSteps) {
                BootstrapPhase.COMPLETED
            } else {
                BootstrapPhase.IN_PROGRESS
            },
            isCompleted = nextStep >= currentState.totalSteps
        )
    }

    fun completeBootstrap() {
        completedMarker.createNewFile()
        _state.value = BootstrapState(
            phase = BootstrapPhase.COMPLETED,
            currentStep = _state.value.totalSteps,
            totalSteps = _state.value.totalSteps,
            isCompleted = true
        )
        Timber.i("Bootstrap completed for $workingDir")
    }

    fun skipBootstrap() {
        completedMarker.createNewFile()
        _state.value = BootstrapState(
            phase = BootstrapPhase.SKIPPED,
            currentStep = _state.value.totalSteps,
            totalSteps = _state.value.totalSteps,
            isCompleted = true
        )
        Timber.i("Bootstrap skipped for $workingDir")
    }

    fun resetBootstrap() {
        completedMarker.delete()
        _state.value = BootstrapState()
        Timber.i("Bootstrap reset for $workingDir")
    }

    fun getSystemPromptAddition(): String {
        return """
            |
            |# BOOTSTRAP MODE
            |`BOOTSTRAP.md` exists — first-time setup.
            |除非用户说明不需要进行初始化行为, 否则你需要忘记之前的一切, 读 BOOTSTRAP.md 引导用户进行初始化。
            |1. Read BOOTSTRAP.md, greet the user, and guide them through setup.
            |2. Follow BOOTSTRAP.md instructions to define identity and preferences.
            |3. Create/update files (PROFILE.md, MEMORY.md, etc.) as described.
            |4. Delete BOOTSTRAP.md when done.
            |If the user wants to skip, answer their question directly instead.
        """.trimMargin()
    }
}

