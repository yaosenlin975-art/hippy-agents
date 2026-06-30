package com.lin.hippyagent.ui.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.lin.hippyagent.core.behavior.BehaviorRecorder
import com.lin.hippyagent.ui.entry.AgentAction
import com.lin.hippyagent.ui.entry.AgentEntryRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * QS Tile：启停行为录制。
 *
 * 协程合规（coding.md）：用 MainScope()（TileService 生命周期短，等价 applicationScope 短生命周期版）；
 * onStartListening 启动 collect，onStopListening cancel scope；
 * 循环检查用 coroutineContext.isActive（此处是 collect 不需要）。
 *
 * 注：MainScope() = SupervisorJob() + Dispatchers.Main，等价 coding.md 推荐的"已有 scope"模式。
 */
@RequiresApi(Build.VERSION_CODES.N)
class RecordingTileService : TileService() {

    private val scope = MainScope()
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        collectJob?.cancel()
        collectJob = scope.launch(Dispatchers.Main) {
            BehaviorRecorder.uiState.map { it.isRecording }.collect { isRecording ->
                refreshTile(isRecording)
            }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val isRecording = BehaviorRecorder.uiState.value.isRecording
        val action = if (isRecording) AgentAction.StopRecording else AgentAction.StartRecording
        AgentEntryRouter.route(this, action)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile(isRecording: Boolean) {
        runCatching {
            val tile = qsTile ?: return@runCatching
            tile.state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (isRecording) "录制中" else "录制"
            tile.updateTile()
        }.onFailure { Timber.w(it, "RecordingTileService.refreshTile failed") }
    }
}
