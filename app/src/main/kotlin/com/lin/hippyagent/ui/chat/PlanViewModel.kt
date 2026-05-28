package com.lin.hippyagent.ui.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.plan.AgentPlanManager
import com.lin.hippyagent.core.agent.plan.CreatePlanTool
import com.lin.hippyagent.core.agent.plan.FinishPlanTool
import com.lin.hippyagent.core.agent.plan.PlanPrompts
import com.lin.hippyagent.core.agent.plan.PlanState
import com.lin.hippyagent.core.agent.plan.ReviseCurrentPlanTool
import com.lin.hippyagent.core.agent.plan.UpdateSubTaskStateTool
import com.lin.hippyagent.core.tools.Tool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class PlanUiState(
    val planEnabled: Boolean = false,
    val currentPlan: PlanState? = null
)

class PlanViewModel(
    private val agentFactory: AgentFactory
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanUiState())
    val uiState: StateFlow<PlanUiState> = _uiState.asStateFlow()

    val planEnabled: StateFlow<Boolean> = _uiState.map { it.planEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val currentPlan: StateFlow<PlanState?> = _uiState.map { it.currentPlan }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var planManager: AgentPlanManager? = null
    private var planToolsRegistered = false

    fun initPlanManager(agentId: String) {
        val newPlanManager = AgentPlanManager(agentId)
        planManager = newPlanManager
        planToolsRegistered = false
        _uiState.update {
            it.copy(
                planEnabled = newPlanManager.isPlanEnabled,
                currentPlan = newPlanManager.currentPlan.value
            )
        }
        viewModelScope.launch {
            newPlanManager.currentPlan.collect { plan ->
                _uiState.update { it.copy(currentPlan = plan) }
            }
        }
    }

    fun setPlanEnabled(enabled: Boolean) {
        planManager?.let { mgr ->
            mgr.setPlanEnabled(enabled)
            _uiState.update { it.copy(planEnabled = enabled) }
        }
    }

    fun togglePlanMode() {
        setPlanEnabled(!_uiState.value.planEnabled)
    }

    fun clearPlan() {
        planManager?.clearPlan()
        _uiState.update { it.copy(currentPlan = null) }
    }

    fun getActivePlan(): PlanState? = _uiState.value.currentPlan

    fun buildPlanContext(): String? {
        val mgr = planManager ?: return null

        val sb = StringBuilder()

        if (mgr.isPlanEnabled) {
            sb.appendLine(PlanPrompts.PLAN_MODE_ENABLED_HINT)
            sb.appendLine()
        } else {
            sb.appendLine(PlanPrompts.PLAN_WORKFLOW_HINT)
            sb.appendLine()
        }

        val plan = mgr.currentPlan.value
        if (plan != null) {
            sb.appendLine(PlanPrompts.buildPlanStateHint(plan))
        }
        return sb.toString()
    }

    fun registerPlanToolsIfNeeded(agent: com.lin.hippyagent.core.agent.Agent) {
        if (planToolsRegistered) return
        val mgr = planManager ?: return

        val planTools: List<Tool> = listOf(
            CreatePlanTool(mgr),
            UpdateSubTaskStateTool(mgr),
            ReviseCurrentPlanTool(mgr),
            FinishPlanTool(mgr)
        )
        agent.registerTools(planTools)
        planToolsRegistered = true
        Timber.d("Plan tools registered for agent: ${agent.profileConfig.agentId}")
    }

    val isPlanEnabled: Boolean get() = planManager?.isPlanEnabled == true
}
