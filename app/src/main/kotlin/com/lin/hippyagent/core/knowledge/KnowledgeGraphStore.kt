package com.lin.hippyagent.core.knowledge

import com.lin.hippyagent.core.agent.session.GraphEntityDao
import com.lin.hippyagent.core.agent.session.GraphEntityEntity
import com.lin.hippyagent.core.agent.session.GraphRelationDao
import com.lin.hippyagent.core.agent.session.GraphRelationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class EntityType { PERSON, PROJECT, TECHNOLOGY, LOCATION, CONCEPT }
enum class RelationType { BELONGS_TO, DEPENDS_ON, RELATED_TO, USED_IN, CREATED_BY }

data class GraphEntity(
    val id: String,
    val type: EntityType,
    val name: String,
    val properties: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)

data class GraphRelation(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val type: RelationType,
    val properties: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)

class KnowledgeGraphStore(
    private val entityDao: GraphEntityDao,
    private val relationDao: GraphRelationDao
) {
    suspend fun addEntity(entity: GraphEntity): Result<GraphEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = entityDao.getByNameAndType(entity.name, entity.type.name)
            if (existing != null) {
                val merged = mergeProperties(existing, entity)
                entityDao.update(merged)
                entity
            } else {
                val now = System.currentTimeMillis()
                entityDao.insert(
                    GraphEntityEntity(
                        id = entity.id,
                        name = entity.name,
                        type = entity.type.name,
                        properties = serializeProperties(entity.properties),
                        source = "",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                entity
            }
        }
    }

    suspend fun addRelation(relation: GraphRelation): Result<GraphRelation> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = relationDao.getBySourceTargetType(
                relation.sourceId, relation.targetId, relation.type.name
            )
            if (existing != null) {
                relation
            } else {
                relationDao.insert(
                    GraphRelationEntity(
                        id = relation.id,
                        sourceEntityId = relation.sourceId,
                        targetEntityId = relation.targetId,
                        relationType = relation.type.name,
                        properties = serializeProperties(relation.properties),
                        createdAt = System.currentTimeMillis()
                    )
                )
                relation
            }
        }
    }

    suspend fun getEntity(id: String): GraphEntity? = withContext(Dispatchers.IO) {
        entityDao.getById(id)?.toDomain()
    }

    suspend fun searchEntities(query: String): List<GraphEntity> = withContext(Dispatchers.IO) {
        entityDao.searchByName("%$query%").map { it.toDomain() }
    }

    suspend fun getEntitiesByType(type: EntityType): List<GraphEntity> = withContext(Dispatchers.IO) {
        entityDao.getByType(type.name).map { it.toDomain() }
    }

    suspend fun getRelationsForEntity(entityId: String): List<GraphRelation> = withContext(Dispatchers.IO) {
        relationDao.getByEntityId(entityId).map { it.toDomain() }
    }

    suspend fun getAllEntities(): List<GraphEntity> = withContext(Dispatchers.IO) {
        entityDao.getAll().map { it.toDomain() }
    }

    suspend fun getAllRelations(): List<GraphRelation> = withContext(Dispatchers.IO) {
        relationDao.getAll().map { it.toDomain() }
    }

    suspend fun removeEntity(id: String) = withContext(Dispatchers.IO) {
        val entity = entityDao.getById(id) ?: return@withContext
        relationDao.getByEntityId(id).forEach { relationDao.delete(it) }
        entityDao.delete(entity)
    }

    private fun mergeProperties(existing: GraphEntityEntity, newEntity: GraphEntity): GraphEntityEntity {
        val existingProps = deserializeProperties(existing.properties)
        val merged = existingProps + newEntity.properties
        return existing.copy(
            properties = serializeProperties(merged),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun GraphEntityEntity.toDomain() = GraphEntity(
        id = id,
        type = EntityType.valueOf(type),
        name = name,
        properties = deserializeProperties(properties),
        confidence = 1.0f
    )

    private fun GraphRelationEntity.toDomain() = GraphRelation(
        id = id,
        sourceId = sourceEntityId,
        targetId = targetEntityId,
        type = RelationType.valueOf(relationType),
        properties = deserializeProperties(properties),
        confidence = 1.0f
    )

    private fun serializeProperties(props: Map<String, String>): String {
        return props.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    private fun deserializeProperties(s: String): Map<String, String> {
        if (s.isBlank() || s == "{}") return emptyMap()
        return s.split(",").associateNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }
}

private inline fun <K, V> Iterable<K>.associateNotNull(transform: (K) -> Pair<K, V>?): Map<K, V> {
    val map = mutableMapOf<K, V>()
    for (item in this) {
        transform(item)?.let { (k, v) -> map[k] = v }
    }
    return map
}

