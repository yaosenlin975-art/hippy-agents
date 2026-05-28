package com.lin.hippyagent.core.agent.session

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "agent_groups",
    indices = [
        Index("groupName"),
        Index("createdAt")
    ]
)
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val groupName: String,
    val agentIds: String,
    val mentionOnlyAgentIds: String = "[]",
    val llmSelectorProviderId: String? = null,
    val llmSelectorModelName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Query("SELECT * FROM agent_groups WHERE groupId = :groupId")
    suspend fun getGroup(groupId: String): GroupEntity?

    @Query("SELECT * FROM agent_groups ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllGroups(limit: Int = 100, offset: Int = 0): List<GroupEntity>

    @Query("DELETE FROM agent_groups WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("UPDATE agent_groups SET groupName = :newName WHERE groupId = :groupId")
    suspend fun renameGroup(groupId: String, newName: String)

    @Query("UPDATE agent_groups SET agentIds = :agentIds WHERE groupId = :groupId")
    suspend fun updateAgentIds(groupId: String, agentIds: String)

    @Query("UPDATE agent_groups SET mentionOnlyAgentIds = :mentionOnlyAgentIds WHERE groupId = :groupId")
    suspend fun updateMentionOnlyAgentIds(groupId: String, mentionOnlyAgentIds: String)
}
