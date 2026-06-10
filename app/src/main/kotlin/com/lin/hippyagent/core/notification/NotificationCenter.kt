package com.lin.hippyagent.core.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber

class NotificationCenter(
    private val dao: NotificationEventDao,
    private val aggregator: NotificationAggregator,
    private val applicationScope: CoroutineScope,
    private val notificationService: HippyAgentNotificationService
) {
    suspend fun notify(event: NotificationEvent) {
        val key = buildAggregateKey(event)
        val isFresh = aggregator.checkDuplicate(key)
        val enriched = event.copy(aggregateKey = key)
        dao.insert(enriched)
        if (!isFresh) {
            Timber.d("NotificationCenter suppressed duplicate: key=$key")
            return
        }
        if (event.priority == NotificationPriority.HIGH || event.priority == NotificationPriority.NORMAL) {
            applicationScope.launch(Dispatchers.Main) {
                runCatching { routeSystemNotification(enriched) }
                    .onFailure { Timber.e(it, "System notification routing failed") }
            }
        }
    }

    fun observe(filter: NotificationFilter? = null): Flow<List<NotificationEvent>> {
        val baseFlow = if (filter?.onlyUnread == true) {
            dao.observeUnread()
        } else {
            dao.observeAll()
        }
        return if (filter == null) {
            baseFlow
        } else {
            baseFlow.map { events -> events.filter { filter.matches(it) } }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun markAsRead(id: String, timestamp: Long = System.currentTimeMillis()) {
        dao.markRead(id, timestamp)
    }

    suspend fun acknowledge(id: String, timestamp: Long = System.currentTimeMillis()) {
        dao.markAcked(id, timestamp)
    }

    suspend fun dismiss(id: String) {
        dao.delete(id)
    }

    suspend fun notifyTaskCompleted(
        taskId: String,
        title: String,
        body: String,
        priority: NotificationPriority = NotificationPriority.NORMAL
    ) {
        notify(
            NotificationEvent(
                type = NotificationType.TASK_COMPLETED,
                priority = priority,
                title = title,
                body = body,
                source = taskId,
                sourceType = "task",
                actions = listOf("open_task", "dismiss"),
                payload = "{\"taskId\":\"$taskId\",\"status\":\"completed\"}"
            )
        )
    }

    /**
     * 任务失败通知 — 用 AGENT_ERROR 类型(带 task 上下文),
     * 不要复用 notifyTaskCompleted,否则通知中心 "已完成" 标签会显示失败任务。
     */
    suspend fun notifyTaskFailed(
        taskId: String,
        title: String,
        body: String,
        priority: NotificationPriority = NotificationPriority.HIGH
    ) {
        notify(
            NotificationEvent(
                type = NotificationType.AGENT_ERROR,
                priority = priority,
                title = title,
                body = body,
                source = taskId,
                sourceType = "task",
                actions = listOf("open_task", "dismiss"),
                payload = "{\"taskId\":\"$taskId\",\"status\":\"failed\"}"
            )
        )
    }

    suspend fun notifyAgentError(agentId: String, error: String) {
        notify(
            NotificationEvent(
                type = NotificationType.AGENT_ERROR,
                priority = NotificationPriority.HIGH,
                title = "Agent 错误: $agentId",
                body = error,
                source = agentId,
                sourceType = "agent",
                actions = listOf("open_log", "dismiss"),
                payload = "{\"agentId\":\"$agentId\"}"
            )
        )
    }

    suspend fun notifyApprovalRequest(
        approvalId: String,
        taskId: String,
        prompt: String,
        options: List<String>
    ) {
        val payload = JSONArray().apply { options.forEach { put(it) } }.toString()
        notify(
            NotificationEvent(
                type = NotificationType.APPROVAL_REQUEST,
                priority = NotificationPriority.HIGH,
                title = "审批请求",
                body = prompt,
                source = approvalId,
                sourceType = "approval",
                actions = options.ifEmpty { listOf("approve", "reject") },
                payload = "{\"approvalId\":\"$approvalId\",\"taskId\":\"$taskId\",\"options\":$payload}"
            )
        )
    }

    suspend fun notifyStatusChange(
        source: String,
        sourceType: String,
        title: String,
        body: String
    ) {
        notify(
            NotificationEvent(
                type = NotificationType.STATUS_CHANGE,
                priority = NotificationPriority.NORMAL,
                title = title,
                body = body,
                source = source,
                sourceType = sourceType,
                actions = listOf("open", "dismiss")
            )
        )
    }

    suspend fun notifySystemMessage(title: String, body: String) {
        notify(
            NotificationEvent(
                type = NotificationType.SYSTEM_MESSAGE,
                priority = NotificationPriority.NORMAL,
                title = title,
                body = body,
                source = "system",
                sourceType = "system",
                actions = listOf("dismiss")
            )
        )
    }

    private fun buildAggregateKey(event: NotificationEvent): String {
        val source = event.source.ifBlank { event.sourceType }
        return "${event.type.name}:$source"
    }

    private fun routeSystemNotification(event: NotificationEvent) {
        when (event.type) {
            NotificationType.TASK_COMPLETED ->
                notificationService.sendTaskCompleteNotification(event.title, event.body)
            NotificationType.AGENT_ERROR ->
                notificationService.sendErrorNotification(event.title, event.body)
            NotificationType.APPROVAL_REQUEST,
            NotificationType.STATUS_CHANGE,
            NotificationType.SYSTEM_MESSAGE ->
                notificationService.sendTaskCompleteNotification(event.title, event.body)
        }
    }
}
