package com.lin.hippyagent.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.lin.hippyagent.R
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.service.AgentForegroundService
import com.lin.hippyagent.core.skill.SkillManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import timber.log.Timber

/**
 * 2×2 Agent 状态小组件：显示运行状态 / 当前模型 / 最近 Skill / 当前任务。
 *
 * 数据源：
 * - AgentForegroundService.isRunning（@Volatile 静态字段）
 * - ModelProviderStore.providers.first()（Flow.first() suspend 取默认 provider）
 *   注：ModelManager 是孤儿类未在 Koin 注册，改用 ModelProviderStore
 * - SkillManager.loadIndex().skills.values.firstOrNull()（同步）
 *   注：SkillIndexManager 未在 Koin 注册，改用 SkillManager.loadIndex()
 * - 当前任务：因 MissionRunner 无状态暴露，显示 "-" 兜底
 *
 * 协程合规（coding.md）：
 * - 复用 Koin 注册的 applicationScope（named("applicationScope")），不自建 CoroutineScope
 */
class AgentStatusWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val appScope = GlobalContext.get().get<CoroutineScope>(named("applicationScope"))
        appScope.launch {
            appWidgetIds.forEach { widgetId ->
                val views = buildRemoteViews(context)
                runCatching {
                    appWidgetManager.updateAppWidget(widgetId, views)
                }.onFailure { Timber.w(it, "AgentStatusWidget onUpdate failed") }
            }
        }
    }

    private suspend fun buildRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_agent_status)

        // 运行状态
        val running = AgentForegroundService.isRunning
        views.setTextViewText(
            R.id.widget_status_row,
            if (running) "状态：运行中" else "状态：空闲"
        )

        // 当前模型（ModelProviderStore.providers Flow.first() suspend 取默认 provider）
        val modelName = runCatching {
            val providers = GlobalContext.get().get<ModelProviderStore>().providers.first()
            (providers.find { it.isDefault } ?: providers.firstOrNull())?.name ?: "unknown"
        }.getOrDefault("unknown")
        views.setTextViewText(R.id.widget_model_row, "模型：$modelName")

        // 最近 Skill（SkillManager.loadIndex 同步）
        val lastSkill = runCatching {
            GlobalContext.get().get<SkillManager>().loadIndex()
                .skills.values.firstOrNull()?.name
        }.getOrDefault(null)
        views.setTextViewText(
            R.id.widget_skill_row,
            "最近 Skill：${lastSkill ?: "—"}"
        )

        // 当前任务（MissionRunner 无状态暴露，兜底显示）
        views.setTextViewText(R.id.widget_task_row, "当前任务：—")

        return views
    }
}
