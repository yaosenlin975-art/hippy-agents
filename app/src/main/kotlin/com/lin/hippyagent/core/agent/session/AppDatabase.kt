package com.lin.hippyagent.core.agent.session

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lin.hippyagent.core.agent.task.TaskDao
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.agent.task.TaskTypeConverters
import com.lin.hippyagent.core.notification.NotificationEvent
import com.lin.hippyagent.core.notification.NotificationEventDao
import com.lin.hippyagent.core.notification.NotificationTypeConverters
import com.lin.hippyagent.core.security.ToolApprovalRule
import com.lin.hippyagent.core.security.ToolApprovalRuleDao
import com.lin.hippyagent.core.task.HippyJobEntity
import com.lin.hippyagent.core.task.HippyInboxEntity

@Database(
    entities = [
        SessionEntity::class,
        SessionStatsEntity::class,
        SessionCompressionEntity::class,
        MessageEntity::class,
        DreamHistoryEntity::class,
        FlowRecordEntity::class,
        FlowStepEntity::class,
        GraphEntityEntity::class,
        GraphRelationEntity::class,
        HippyJobEntity::class,
        HippyInboxEntity::class,
        SessionGroupEntity::class,
        InboxEvent::class,
        GroupEntity::class,
        NotificationEvent::class,
        TaskEntity::class,
        ToolApprovalRule::class
    ],
    version = 24,
    exportSchema = true
)
@TypeConverters(NotificationTypeConverters::class, TaskTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sessionStatsDao(): SessionStatsDao
    abstract fun sessionCompressionDao(): SessionCompressionDao
    abstract fun messageDao(): MessageDao
    abstract fun dreamHistoryDao(): DreamHistoryDao
    abstract fun flowRecordDao(): FlowRecordDao
    abstract fun flowStepDao(): FlowStepDao
    abstract fun graphEntityDao(): GraphEntityDao
    abstract fun graphRelationDao(): GraphRelationDao
    abstract fun insightsDao(): InsightsDao
    abstract fun hippyJobDao(): com.lin.hippyagent.core.task.HippyJobDao
    abstract fun sessionGroupDao(): SessionGroupDao
    abstract fun inboxDao(): InboxDao
    abstract fun groupDao(): GroupDao
    abstract fun notificationEventDao(): NotificationEventDao
    abstract fun taskDao(): TaskDao
    abstract fun toolApprovalRuleDao(): ToolApprovalRuleDao
}
