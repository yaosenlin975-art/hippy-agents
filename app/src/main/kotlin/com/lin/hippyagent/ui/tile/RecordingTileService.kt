package com.lin.hippyagent.ui.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.lin.hippyagent.core.behavior.BehaviorRecordingController
import com.lin.hippyagent.core.behavior.RecordingState
import com.lin.hippyagent.ui.floatwindow.BehaviorRecordingFloatWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * QS Tile：启停行为录制。
 *
 * Plan G 入口点：调用 BehaviorRecordingController.start()/stop() 控制 BehaviorRecorder + DeeplinkBookmarkSession，
 * 并通过 BehaviorRecordingFloatWindow 显示录制浮窗（收藏当前页 / 停止按钮）。
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
            controller().uiState.map { it.state == RecordingState.RECORDING }.collect { isRecording ->
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
        val controller = controller()
        val isRecording = controller.uiState.value.state == RecordingState.RECORDING
        if (isRecording) {
            controller.stop()
            BehaviorRecordingFloatWindow.dismiss()
        } else {
            val started = controller.start()
            if (started) {
                BehaviorRecordingFloatWindow.show(applicationContext, controller)
            } else {
                Timber.w("RecordingTileService.onClick: controller.start() failed (accessibility not running?)")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun controller(): BehaviorRecordingController = GlobalContext.get().get()

    private fun refreshTile(isRecording: Boolean) {
        runCatching {
            val tile = qsTile ?: return@runCatching
            tile.state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (isRecording) getString(com.lin.hippyagent.R.string.tile_recording_active_label) else getString(com.lin.hippyagent.R.string.tile_recording_label)
            tile.updateTile()
        }.onFailure { Timber.w(it, "RecordingTileService.refreshTile failed") }
    }
}
