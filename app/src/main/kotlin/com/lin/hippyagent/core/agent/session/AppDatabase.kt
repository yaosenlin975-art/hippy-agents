package com.lin.hippyagent.core.agent.session

import androidx.room.Database
import androidx.room.RoomDatabase
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
        PendingApproval::class,
        GroupEntity::class
    ],
    version = 20,
    exportSchema = true
)
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
}
