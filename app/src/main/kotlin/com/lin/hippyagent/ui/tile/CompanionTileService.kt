package com.lin.hippyagent.ui.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.lin.hippyagent.core.companion.CompanionController
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
 * QS Tile：切换 Companion 模式。
 *
 * 协程合规：同 RecordingTileService，用 MainScope + collect + cancel。
 */
@RequiresApi(Build.VERSION_CODES.N)
class CompanionTileService : TileService() {

    private val scope = MainScope()
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        collectJob?.cancel()
        collectJob = scope.launch(Dispatchers.Main) {
            CompanionController.uiState.map { it.isActive }.collect { isActive ->
                refreshTile(isActive)
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
        AgentEntryRouter.route(this, AgentAction.CompanionMode)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile(isActive: Boolean) {
        runCatching {
            val tile = qsTile ?: return@runCatching
            tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (isActive) getString(com.lin.hippyagent.R.string.tile_companion_active_label) else getString(com.lin.hippyagent.R.string.tile_companion_label)
            tile.updateTile()
        }.onFailure { Timber.w(it, "CompanionTileService.refreshTile failed") }
    }
}
