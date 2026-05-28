package com.lin.hippyagent.core.agent.collaboration

import android.content.Context
import com.lin.hippyagent.core.agent.session.GroupDao
import com.lin.hippyagent.core.agent.session.GroupEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class GroupInfo(
    val groupId: String,
    val groupName: String,
    val agentIds: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val mentionOnlyAgentIds: List<String> = emptyList(),
    val llmSelectorProviderId: String? = null,
    val llmSelectorModelName: String? = null
)

class GroupRegistry(
    private val dao: GroupDao,
    private val context: Context
) {
    private val groups = mutableMapOf<String, GroupInfo>()
    private val _groupsFlow = MutableStateFlow<List<GroupInfo>>(emptyList())
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob())

    private val persistenceFile: File
        get() = File(context.filesDir, "groups.json")

    val groupsFlow: StateFlow<List<GroupInfo>> = _groupsFlow

    init {
        loadFromRoom()
        migrateFromJson()
    }

    fun createGroup(groupId: String, groupName: String, agentIds: List<String>): GroupInfo {
        val group = GroupInfo(
            groupId = groupId,
            groupName = groupName,
            agentIds = agentIds
        )
        groups[groupId] = group
        _groupsFlow.tryEmit(groups.values.toList())
        scope.launch { dao.insertGroup(group.toEntity()) }
        Timber.i("Created group: $groupName with agents: $agentIds")
        return group
    }

    fun getGroup(groupId: String): GroupInfo? = groups[groupId]

    fun deleteGroup(groupId: String) {
        groups.remove(groupId)
        _groupsFlow.tryEmit(groups.values.toList())
        scope.launch { dao.deleteGroup(groupId) }
        Timber.i("Deleted group: $groupId")
    }

    fun addAgentToGroup(groupId: String, agentId: String): Boolean {
        val group = groups[groupId] ?: return false
        if (agentId in group.agentIds) return false
        groups[groupId] = group.copy(agentIds = group.agentIds + agentId)
        _groupsFlow.tryEmit(groups.values.toList())
        scope.launch { dao.updateAgentIds(groupId, json.encodeToString(group.agentIds + agentId)) }
        return true
    }

    fun renameGroup(groupId: String, newName: String): Boolean {
        val group = groups[groupId] ?: return false
        groups[groupId] = group.copy(groupName = newName)
        _groupsFlow.tryEmit(groups.values.toList())
        scope.launch { dao.renameGroup(groupId, newName) }
        Timber.i("Renamed group: $groupId -> $newName")
        return true
    }

    fun updateGroup(updated: GroupInfo) {
        groups[updated.groupId] = updated
        _groupsFlow.tryEmit(groups.values.toList())
        scope.launch { dao.insertGroup(updated.toEntity()) }
    }

    fun removeAgentFromGroup(groupId: String, agentId: String): Boolean {
        val group = groups[groupId] ?: return false
        groups[groupId] = group.copy(agentIds = group.agentIds - agentId)
        _groupsFlow.tryEmit(groups.values.toList())
        scope.launch { dao.updateAgentIds(groupId, json.encodeToString(group.agentIds - agentId)) }
        return true
    }

    fun listGroups(): List<GroupInfo> = groups.values.toList()

    private fun loadFromRoom() {
        try {
            kotlinx.coroutines.runBlocking {
                val entities = dao.getAllGroups()
                entities.forEach { entity ->
                    groups[entity.groupId] = entity.toGroupInfo()
                }
                _groupsFlow.tryEmit(groups.values.toList())
                Timber.i("Loaded ${entities.size} groups from Room")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load groups from Room")
        }
    }

    private fun migrateFromJson() {
        try {
            if (!persistenceFile.exists()) return
            val data = persistenceFile.readText()
            val loaded = json.decodeFromString<List<GroupInfo>>(data)
            if (loaded.isEmpty()) return
            kotlinx.coroutines.runBlocking {
                loaded.forEach { group ->
                    if (groups.containsKey(group.groupId)) return@forEach
                    groups[group.groupId] = group
                    dao.insertGroup(group.toEntity())
                }
            }
            _groupsFlow.tryEmit(groups.values.toList())
            persistenceFile.delete()
            Timber.i("Migrated ${loaded.size} groups from JSON to Room")
        } catch (e: Exception) {
            Timber.e(e, "Failed to migrate groups from JSON")
        }
    }

    private fun GroupInfo.toEntity() = GroupEntity(
        groupId = groupId,
        groupName = groupName,
        agentIds = json.encodeToString(agentIds),
        mentionOnlyAgentIds = json.encodeToString(mentionOnlyAgentIds),
        llmSelectorProviderId = llmSelectorProviderId,
        llmSelectorModelName = llmSelectorModelName,
        createdAt = createdAt
    )

    private fun GroupEntity.toGroupInfo() = GroupInfo(
        groupId = groupId,
        groupName = groupName,
        agentIds = json.decodeFromString<List<String>>(agentIds),
        createdAt = createdAt,
        mentionOnlyAgentIds = json.decodeFromString<List<String>>(mentionOnlyAgentIds),
        llmSelectorProviderId = llmSelectorProviderId,
        llmSelectorModelName = llmSelectorModelName
    )
}
