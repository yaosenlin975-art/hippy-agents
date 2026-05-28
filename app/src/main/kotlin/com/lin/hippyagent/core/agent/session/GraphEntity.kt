package com.lin.hippyagent.core.agent.session

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete

@Entity(
    tableName = "graph_entities",
    indices = [
        Index("name", "type"),
        Index("type"),
        Index("name")
    ]
)
data class GraphEntityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val properties: String = "{}",
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "graph_relations",
    indices = [
        Index("sourceEntityId"),
        Index("targetEntityId"),
        Index("sourceEntityId", "targetEntityId", "relationType")
    ]
)
data class GraphRelationEntity(
    @PrimaryKey val id: String,
    val sourceEntityId: String,
    val targetEntityId: String,
    val relationType: String,
    val properties: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface GraphEntityDao {
    @Insert
    suspend fun insert(entity: GraphEntityEntity)

    @Update
    suspend fun update(entity: GraphEntityEntity)

    @Delete
    suspend fun delete(entity: GraphEntityEntity)

    @Query("SELECT * FROM graph_entities WHERE id = :id")
    suspend fun getById(id: String): GraphEntityEntity?

    @Query("SELECT * FROM graph_entities WHERE name = :name AND type = :type LIMIT 1")
    suspend fun getByNameAndType(name: String, type: String): GraphEntityEntity?

    @Query("SELECT * FROM graph_entities WHERE type = :type LIMIT :limit OFFSET :offset")
    suspend fun getByType(type: String, limit: Int = 100, offset: Int = 0): List<GraphEntityEntity>

    @Query("SELECT * FROM graph_entities WHERE name LIKE :query LIMIT :limit OFFSET :offset")
    suspend fun searchByName(query: String, limit: Int = 100, offset: Int = 0): List<GraphEntityEntity>

    @Query("SELECT * FROM graph_entities LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int = 100, offset: Int = 0): List<GraphEntityEntity>
}

@Dao
interface GraphRelationDao {
    @Insert
    suspend fun insert(entity: GraphRelationEntity)

    @Delete
    suspend fun delete(entity: GraphRelationEntity)

    @Query("SELECT * FROM graph_relations WHERE sourceEntityId = :entityId OR targetEntityId = :entityId LIMIT :limit OFFSET :offset")
    suspend fun getByEntityId(entityId: String, limit: Int = 100, offset: Int = 0): List<GraphRelationEntity>

    @Query("SELECT * FROM graph_relations WHERE sourceEntityId = :sourceId AND targetEntityId = :targetId AND relationType = :type LIMIT 1")
    suspend fun getBySourceTargetType(sourceId: String, targetId: String, type: String): GraphRelationEntity?

    @Query("SELECT * FROM graph_relations LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int = 100, offset: Int = 0): List<GraphRelationEntity>
}
