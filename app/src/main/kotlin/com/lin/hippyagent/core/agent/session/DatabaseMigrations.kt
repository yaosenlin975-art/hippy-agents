package com.lin.hippyagent.core.agent.session

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN lastMessage TEXT DEFAULT NULL")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT NOT NULL DEFAULT 'android'")
        db.execSQL("ALTER TABLE sessions ADD COLUMN model TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
        db.execSQL("ALTER TABLE sessions ADD COLUMN inputTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN outputTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN cacheReadTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN cacheWriteTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN estimatedCostUsd REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE sessions ADD COLUMN finishedAt INTEGER DEFAULT NULL")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dream_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                triggeredAt INTEGER NOT NULL,
                finishedAt INTEGER DEFAULT NULL,
                status TEXT NOT NULL,
                message TEXT NOT NULL,
                backupPath TEXT DEFAULT NULL,
                sizeBefore INTEGER NOT NULL DEFAULT 0,
                sizeAfter INTEGER NOT NULL DEFAULT 0,
                tokensUsed INTEGER DEFAULT NULL,
                elapsedMs INTEGER DEFAULT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS flow_records (
                id TEXT PRIMARY KEY NOT NULL,
                agentId TEXT NOT NULL,
                name TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'queued',
                revision INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                finishedAt INTEGER DEFAULT NULL,
                result TEXT DEFAULT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS flow_steps (
                id TEXT PRIMARY KEY NOT NULL,
                flowId TEXT NOT NULL,
                stepIndex INTEGER NOT NULL,
                taskDescription TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                result TEXT DEFAULT NULL,
                createdAt INTEGER NOT NULL,
                finishedAt INTEGER DEFAULT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS graph_entities (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                properties TEXT NOT NULL DEFAULT '{}',
                source TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS graph_relations (
                id TEXT PRIMARY KEY NOT NULL,
                sourceEntityId TEXT NOT NULL,
                targetEntityId TEXT NOT NULL,
                relationType TEXT NOT NULL,
                properties TEXT NOT NULL DEFAULT '{}',
                createdAt INTEGER NOT NULL
            )
        """)
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS paw_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                queue TEXT NOT NULL DEFAULT 'default',
                status TEXT NOT NULL DEFAULT 'WAITING',
                priority INTEGER NOT NULL DEFAULT 0,
                dataJson TEXT NOT NULL DEFAULT '{}',
                maxAttempts INTEGER NOT NULL DEFAULT 3,
                attemptsMade INTEGER NOT NULL DEFAULT 0,
                backoffType TEXT NOT NULL DEFAULT 'EXPONENTIAL',
                backoffDelayMs INTEGER NOT NULL DEFAULT 5000,
                backoffJitter REAL NOT NULL DEFAULT 0.1,
                maxStalled INTEGER NOT NULL DEFAULT 3,
                lockToken TEXT DEFAULT NULL,
                lockUntil INTEGER DEFAULT NULL,
                stalledCounter INTEGER NOT NULL DEFAULT 0,
                delayUntil INTEGER DEFAULT NULL,
                parentJobId INTEGER DEFAULT NULL,
                onChildFail TEXT NOT NULL DEFAULT 'CONTINUE',
                timeoutMs INTEGER DEFAULT NULL,
                timeoutAt INTEGER DEFAULT NULL,
                depth INTEGER NOT NULL DEFAULT 0,
                idempotencyKey TEXT DEFAULT NULL,
                maxWaiting INTEGER DEFAULT NULL,
                tokensInput INTEGER NOT NULL DEFAULT 0,
                tokensOutput INTEGER NOT NULL DEFAULT 0,
                resultJson TEXT DEFAULT NULL,
                errorText TEXT DEFAULT NULL,
                progressJson TEXT DEFAULT NULL,
                createdAt INTEGER NOT NULL,
                startedAt INTEGER DEFAULT NULL,
                finishedAt INTEGER DEFAULT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_paw_jobs_status_priority_createdAt ON paw_jobs(status, priority, createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_paw_jobs_name_status ON paw_jobs(name, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_paw_jobs_parentJobId ON paw_jobs(parentJobId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_paw_jobs_idempotencyKey ON paw_jobs(idempotencyKey)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS paw_inbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                jobId INTEGER NOT NULL,
                sender TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                readAt INTEGER DEFAULT NULL,
                sentAt INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_paw_inbox_jobId ON paw_inbox(jobId)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN unreadCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN lastReadMessageId TEXT DEFAULT NULL")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN compressedSummary TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN isCompressed INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN toolName TEXT DEFAULT NULL")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_agentId ON sessions(agentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_lastUpdatedAt ON sessions(lastUpdatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_isPinned ON sessions(isPinned)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages(timestamp)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN metadataJson TEXT DEFAULT NULL")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN groupId TEXT DEFAULT NULL")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS session_groups (
                id TEXT PRIMARY KEY NOT NULL,
                agentId TEXT NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                isCollapsed INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_session_groups_agentId ON session_groups(agentId)")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dream_history_triggeredAt ON dream_history(triggeredAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_flow_records_agentId ON flow_records(agentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_flow_records_status ON flow_records(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_flow_records_createdAt ON flow_records(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_flow_steps_flowId ON flow_steps(flowId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_graph_entities_name_type ON graph_entities(name, type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_graph_entities_type ON graph_entities(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_graph_entities_name ON graph_entities(name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_graph_relations_sourceEntityId ON graph_relations(sourceEntityId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_graph_relations_targetEntityId ON graph_relations(targetEntityId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_graph_relations_source_target_type ON graph_relations(sourceEntityId, targetEntityId, relationType)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memories (id TEXT NOT NULL, user_key TEXT NOT NULL, type TEXT NOT NULL, summary TEXT NOT NULL, detail TEXT, scope TEXT NOT NULL, evidence_kind TEXT NOT NULL, confidence REAL NOT NULL, importance REAL NOT NULL, durability REAL NOT NULL, evidence_count INTEGER NOT NULL, dismissed INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, last_used_at INTEGER, PRIMARY KEY(id))")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(content='memories', summary, detail)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_type_dismissed ON memories(type, dismissed)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_dismissed_updated_at ON memories(dismissed, updated_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_scope_evidence_dismissed_last_seen ON memories(scope, evidence_kind, dismissed, last_seen_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_last_seen_at ON memories(last_seen_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_updated_at ON memories(updated_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_queue_status_priority_createdAt ON task_queue(status, priority, createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_queue_status_completedAt ON task_queue(status, completedAt)")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN senderId TEXT DEFAULT NULL")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN interrupted INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inbox_events (
                id TEXT PRIMARY KEY NOT NULL,
                agentId TEXT NOT NULL DEFAULT 'default',
                sourceType TEXT NOT NULL,
                sourceId TEXT NOT NULL DEFAULT '',
                eventType TEXT NOT NULL,
                status TEXT NOT NULL,
                severity TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                payload TEXT NOT NULL DEFAULT '{}',
                read INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_events_createdAt ON inbox_events(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_events_read ON inbox_events(read)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_events_agentId ON inbox_events(agentId)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_approvals (
                requestId TEXT PRIMARY KEY NOT NULL,
                sessionId TEXT NOT NULL,
                agentId TEXT NOT NULL,
                toolName TEXT NOT NULL,
                severity TEXT NOT NULL DEFAULT 'medium',
                findingsCount INTEGER NOT NULL DEFAULT 0,
                findingsSummary TEXT NOT NULL DEFAULT '',
                toolParams TEXT NOT NULL DEFAULT '{}',
                timeoutSeconds REAL NOT NULL DEFAULT 300.0,
                status TEXT NOT NULL DEFAULT 'pending',
                createdAt INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_approvals_status ON pending_approvals(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_approvals_agentId ON pending_approvals(agentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_approvals_createdAt ON pending_approvals(createdAt)")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_agentId_isPinned_lastUpdatedAt ON sessions(agentId, isPinned, lastUpdatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_isCompressed_timestamp ON messages(sessionId, isCompressed, timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_approvals_status_createdAt ON pending_approvals(status, createdAt)")
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS agent_groups (
                groupId TEXT PRIMARY KEY NOT NULL,
                groupName TEXT NOT NULL,
                agentIds TEXT NOT NULL,
                mentionOnlyAgentIds TEXT NOT NULL DEFAULT '[]',
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_groups_groupName ON agent_groups(groupName)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_groups_createdAt ON agent_groups(createdAt)")
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agent_groups ADD COLUMN llmSelectorProviderId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE agent_groups ADD COLUMN llmSelectorModelName TEXT DEFAULT NULL")
        db.execSQL("UPDATE sessions SET agentId = 'default-agent' WHERE agentId = 'default'")
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE paw_jobs RENAME TO hippy_jobs")
        db.execSQL("ALTER TABLE paw_inbox RENAME TO hippy_inbox")
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS session_stats (
                sessionId TEXT NOT NULL PRIMARY KEY,
                inputTokens INTEGER NOT NULL DEFAULT 0,
                outputTokens INTEGER NOT NULL DEFAULT 0,
                cacheReadTokens INTEGER NOT NULL DEFAULT 0,
                cacheWriteTokens INTEGER NOT NULL DEFAULT 0,
                estimatedCostUsd REAL DEFAULT NULL,
                finishedAt INTEGER DEFAULT NULL,
                FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS session_compression (
                sessionId TEXT NOT NULL PRIMARY KEY,
                compressedSummary TEXT DEFAULT NULL,
                FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("""
            INSERT INTO session_stats (sessionId, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, estimatedCostUsd, finishedAt)
            SELECT id, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, estimatedCostUsd, finishedAt FROM sessions
        """)
        db.execSQL("""
            INSERT INTO session_compression (sessionId, compressedSummary)
            SELECT id, compressedSummary FROM sessions WHERE compressedSummary IS NOT NULL
        """)
        db.execSQL("""
            CREATE TABLE sessions_new (
                id TEXT NOT NULL PRIMARY KEY,
                agentId TEXT NOT NULL,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                lastUpdatedAt INTEGER NOT NULL,
                messageCount INTEGER NOT NULL DEFAULT 0,
                isPinned INTEGER NOT NULL DEFAULT 0,
                tags TEXT NOT NULL DEFAULT '',
                lastMessage TEXT DEFAULT NULL,
                model TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'active',
                unreadCount INTEGER NOT NULL DEFAULT 0,
                isMuted INTEGER NOT NULL DEFAULT 0,
                groupId TEXT DEFAULT NULL,
                interrupted INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            INSERT INTO sessions_new (id, agentId, title, createdAt, lastUpdatedAt, messageCount, isPinned, tags, lastMessage, model, status, unreadCount, isMuted, groupId, interrupted, hidden)
            SELECT id, agentId, title, createdAt, lastUpdatedAt, messageCount, isPinned, tags, lastMessage, model, status, unreadCount, isMuted, groupId, interrupted, hidden FROM sessions
        """)
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_agentId ON sessions(agentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_lastUpdatedAt ON sessions(lastUpdatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_isPinned ON sessions(isPinned)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_agentId_isPinned_lastUpdatedAt ON sessions(agentId, isPinned, lastUpdatedAt)")
    }
}

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notification_events (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                priority TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                source TEXT NOT NULL,
                sourceType TEXT NOT NULL,
                actions TEXT NOT NULL DEFAULT '[]',
                payload TEXT DEFAULT NULL,
                created_at INTEGER NOT NULL,
                read_at INTEGER DEFAULT NULL,
                acked_at INTEGER DEFAULT NULL,
                aggregate_key TEXT DEFAULT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_type ON notification_events(type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_created_at ON notification_events(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_read_at ON notification_events(read_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_events_type_created_at ON notification_events(type, created_at)")
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS executable_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                sessionId TEXT DEFAULT NULL,
                status TEXT NOT NULL,
                steps TEXT NOT NULL DEFAULT '[]',
                executionContext TEXT NOT NULL DEFAULT '{}',
                approvalNodes TEXT NOT NULL DEFAULT '[]',
                result TEXT DEFAULT NULL,
                error_message TEXT DEFAULT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER DEFAULT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_executable_tasks_status ON executable_tasks(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_executable_tasks_created_at ON executable_tasks(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_executable_tasks_agent_id ON executable_tasks(agent_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_executable_tasks_status_created_at ON executable_tasks(status, created_at)")
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // executable_tasks 加 source 列, 默认 'task' 兼容旧数据
        db.execSQL("ALTER TABLE executable_tasks ADD COLUMN source TEXT NOT NULL DEFAULT 'task'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_executable_tasks_source ON executable_tasks(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_executable_tasks_status_source_created_at ON executable_tasks(status, source, created_at)")

        // tool_approval_rules 表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tool_approval_rules (
                key TEXT NOT NULL PRIMARY KEY,
                action TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                arg_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_approval_rules_tool_name ON tool_approval_rules(tool_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_approval_rules_tool_name_arg_hash ON tool_approval_rules(tool_name, arg_hash)")
    }
}

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 审批已 2026-06 全部迁到 Task 审批系统, pending_approvals 表彻底作废
        // DROP TABLE 清空旧数据 + 4 个相关索引
        db.execSQL("DROP TABLE IF EXISTS pending_approvals")
    }
}

val ALL_MIGRATIONS = listOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
    MIGRATION_17_18,
    MIGRATION_18_19,
    MIGRATION_19_20,
    MIGRATION_20_21,
    MIGRATION_21_22,
    MIGRATION_22_23,
    MIGRATION_23_24
)
