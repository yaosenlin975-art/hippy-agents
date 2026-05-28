package com.lin.hippyagent.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.insights.InsightsEngine
import com.lin.hippyagent.core.insights.InsightsReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class InsightsViewModel(
    private val insightsEngine: InsightsEngine
) : ViewModel() {
    private val _report = MutableStateFlow<InsightsReport?>(null)
    val report: StateFlow<InsightsReport?> = _report

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _selectedDays = MutableStateFlow(30)
    val selectedDays: StateFlow<Int> = _selectedDays

    init {
        refresh()
    }

    fun refresh() {
        _report.value = null
        viewModelScope.launch {
            _loading.value = true
            insightsEngine.generate(_selectedDays.value)
                .onSuccess { _report.value = it }
                .onFailure { timber.log.Timber.w(it, "Insights refresh failed") }
            _loading.value = false
        }
    }

    fun setDays(days: Int) {
        _selectedDays.value = days
        refresh()
    }
}

