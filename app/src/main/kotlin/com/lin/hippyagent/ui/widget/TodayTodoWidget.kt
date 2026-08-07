package com.lin.hippyagent.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.lin.hippyagent.R
import com.lin.hippyagent.core.cron.CronJobManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 4×1 今日待办小组件：显示今日 CronJob 列表（最多 5 项）。
 *
 * 数据源：CronJobManager.getEnabledJobs()（@Volatile jobsSnapshot 的公开过滤视图）
 * - 因 CronJob.schedule 是 cron 表达式字符串，本 Widget 简化处理：只显示 enabled=true 的所有 CronJob 名
 * - 真正"今日"过滤需要解析 cron 表达式，超出本 plan 范围
 *
 * 协程合规：
 * - 复用 Koin 注册的 applicationScope（named("applicationScope")），不自建 CoroutineScope
 * - 不用 Thread.sleep / isActive / 裸 CoroutineScope()
 */
class TodayTodoWidget : AppWidgetProvider() {

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
                }.onFailure { Timber.w(it, "TodayTodoWidget onUpdate failed") }
            }
        }
    }

    private suspend fun buildRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today_todo)

        // 时间显示
        val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        views.setTextViewText(R.id.widget_time, timeStr)

        // 查询 CronJobManager（getEnabledJobs 已经过滤 enabled=true）
        val enabledJobs = runCatching {
            GlobalContext.get().get<CronJobManager>().getEnabledJobs()
        }.getOrDefault(emptyList()).take(5)

        views.setTextViewText(R.id.widget_item_1, enabledJobs.getOrNull(0)?.name ?: "—")
        views.setTextViewText(R.id.widget_item_2, enabledJobs.getOrNull(1)?.name ?: "")
        views.setTextViewText(R.id.widget_item_3, enabledJobs.getOrNull(2)?.name ?: "")
        views.setTextViewText(R.id.widget_item_4, enabledJobs.getOrNull(3)?.name ?: "")
        views.setTextViewText(R.id.widget_item_5, enabledJobs.getOrNull(4)?.name ?: "")

        return views
    }
}
