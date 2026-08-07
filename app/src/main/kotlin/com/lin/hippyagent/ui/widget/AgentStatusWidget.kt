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
 * 协程合规：
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
            context.getString(if (running) R.string.widget_status_running else R.string.widget_status_idle)
        )

        // 当前模型（ModelProviderStore.providers Flow.first() suspend 取默认 provider）
        val modelName = runCatching {
            val providers = GlobalContext.get().get<ModelProviderStore>().providers.first()
            (providers.find { it.isDefault } ?: providers.firstOrNull())?.name ?: "unknown"
        }.getOrDefault("unknown")
        views.setTextViewText(R.id.widget_model_row, context.getString(R.string.widget_model_label, modelName))

        // 当前激活 Skill（SkillLifecycleManager.getActiveSkillIds() 返回当前激活的 Skill 集合，比 loadIndex().skills.firstOrNull 更准确）
        val activeSkillName = runCatching {
            val skillManager = GlobalContext.get().get<SkillManager>()
            val activeId = GlobalContext.get().get<com.lin.hippyagent.core.skill.SkillLifecycleManager>()
                .getActiveSkillIds().firstOrNull()
            activeId?.let { skillManager.getManifest(it)?.name }
        }.getOrDefault(null)
        views.setTextViewText(
            R.id.widget_skill_row,
            context.getString(R.string.widget_skill_label, activeSkillName ?: "—")
        )

        // 当前任务（MissionRunner 无状态暴露，兜底显示）
        views.setTextViewText(R.id.widget_task_row, context.getString(R.string.widget_task_label, "—"))

        return views
    }
}
