package com.lin.hippyagent.ui.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.lin.hippyagent.core.service.AgentForegroundService
import com.lin.hippyagent.ui.entry.AgentAction
import com.lin.hippyagent.ui.entry.AgentEntryRouter
import timber.log.Timber

/**
 * QS Tile：切换 Agent 前台服务运行状态。
 *
 * - onStartListening：刷新 tile 状态（查 AgentForegroundService.isRunning）
 * - onClick：通过 AgentEntryRouter.route 触发 ToggleAgent
 * - onStopListening：无需 collect（isRunning 是 @Volatile 静态字段，每次 onStartListening 读最新值）
 *
 * 注：因 AgentForegroundService.isRunning 不是 Flow，本 Tile 用 onStartListening 时查询模式。
 * 状态变化时用户下拉通知栏会触发 onStartListening 重新读取，满足 spec 6.3。
 */
@RequiresApi(Build.VERSION_CODES.N)
class AgentToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        AgentEntryRouter.route(this, AgentAction.ToggleAgent)
        refreshTile()
    }

    private fun refreshTile() {
        runCatching {
            val tile = qsTile ?: return@runCatching
            val running = AgentForegroundService.isRunning
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (running) "运行中" else "Agent"
            tile.updateTile()
        }.onFailure { Timber.w(it, "AgentToggleTileService.refreshTile failed") }
    }
}
