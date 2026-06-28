package com.lin.hippyagent.ui.trace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.trace.TraceRepository
import com.lin.hippyagent.data.SpanTypeStatsRow
import com.lin.hippyagent.data.TraceSpanEntity
import com.lin.hippyagent.data.TraceSummaryRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class TraceListUiState(
    val isLoading: Boolean = false,
    val traces: List<TraceSummaryRow> = emptyList(),
    val error: String? = null
)

@Immutable
data class TraceDetailUiState(
    val isLoading: Boolean = false,
    val spans: List<TraceSpanEntity> = emptyList(),
    val error: String? = null
)

@Immutable
data class TraceStatsUiState(
    val isLoading: Boolean = false,
    val stats: List<SpanTypeStatsRow> = emptyList(),
    val timeRangeDays: Int = 7,
    val error: String? = null
)

class TraceViewModel(
    private val repository: TraceRepository
) : ViewModel() {

    private val _listState = MutableStateFlow(TraceListUiState(isLoading = true))
    val listState: StateFlow<TraceListUiState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow(TraceDetailUiState())
    val detailState: StateFlow<TraceDetailUiState> = _detailState.asStateFlow()

    private val _statsState = MutableStateFlow(TraceStatsUiState(isLoading = true))
    val statsState: StateFlow<TraceStatsUiState> = _statsState.asStateFlow()

    fun loadTraceList() {
        viewModelScope.launch {
            _listState.value = _listState.value.copy(isLoading = true, error = null)
            runCatching {
                repository.getTraceList(limit = 50, offset = 0)
            }.onSuccess { traces ->
                _listState.value = TraceListUiState(isLoading = false, traces = traces)
            }.onFailure { e ->
                _listState.value = TraceListUiState(isLoading = false, error = e.message)
            }
        }
    }

    fun loadTraceDetail(traceId: String) {
        viewModelScope.launch {
            _detailState.value = _detailState.value.copy(isLoading = true, error = null)
            runCatching {
                repository.getSpansByTraceId(traceId)
            }.onSuccess { spans ->
                _detailState.value = TraceDetailUiState(isLoading = false, spans = spans)
            }.onFailure { e ->
                _detailState.value = TraceDetailUiState(isLoading = false, error = e.message)
            }
        }
    }

    fun loadStats(timeRangeDays: Int) {
        viewModelScope.launch {
            _statsState.value = _statsState.value.copy(isLoading = true, timeRangeDays = timeRangeDays)
            val since = System.currentTimeMillis() - timeRangeDays * 24 * 3600 * 1000L
            runCatching {
                repository.getAggregateStats(since)
            }.onSuccess { stats ->
                _statsState.value = TraceStatsUiState(isLoading = false, stats = stats, timeRangeDays = timeRangeDays)
            }.onFailure { e ->
                _statsState.value = TraceStatsUiState(isLoading = false, error = e.message, timeRangeDays = timeRangeDays)
            }
        }
    }

    fun clearAllTraces() {
        viewModelScope.launch {
            runCatching { repository.deleteAll() }
            loadTraceList()
        }
    }
}
