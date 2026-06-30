package com.lin.hippyagent.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.MainActivity
import com.lin.hippyagent.ui.entry.AgentEntryRouter

/**
 * 4×2 快速提问小组件：纯入口，点击跳 MainActivity 进入快速提问模式。
 *
 * 注：spec 3.5.2 提到 RemoteViews EditText 在桌面有 IME 限制，
 * 实际行为：点击整个 widget 启动 MainActivity + EXTRA_QUICK_ASK=true。
 */
class QuickAskWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AgentEntryRouter.EXTRA_AGENT_ACTION, AgentEntryRouter.ACTION_OPEN_CHAT)
            putExtra(AgentEntryRouter.EXTRA_QUICK_ASK, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        appWidgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_quick_ask)
            views.setOnClickPendingIntent(R.id.widget_hint, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_ask_button, pendingIntent)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
