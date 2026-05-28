package com.lin.hippyagent.core.agent.subagent

import com.lin.hippyagent.core.task.HippyJobDao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import timber.log.Timber

class SubAgentAggregator(private val dao: HippyJobDao) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun aggregate(parentJobId: Long): AggregatedResult {
        val inboxMessages = dao.getUnreadInbox(parentJobId)
        dao.markInboxRead(parentJobId)

        val children = inboxMessages
            .filter { it.sender == "minions" }
            .mapNotNull { msg ->
                try {
                    parseChildDone(msg.payloadJson)
                } catch (e: Exception) {
                    Timber.w(e, "SubAgentAggregator: failed to parse inbox message ${msg.id}")
                    null
                }
            }

        val completed = children.count { it.status == "completed" }
        val failed = children.count { it.status == "failed" }

        return AggregatedResult(
            total = children.size,
            completed = completed,
            failed = failed,
            children = children,
            summary = buildSummary(children, completed, failed)
        )
    }

    private fun parseChildDone(payloadJson: String): ChildResult {
        val obj = json.parseToJsonElement(payloadJson).jsonObject
        val childId = obj["child_id"]?.jsonPrimitive?.long ?: 0L
        val jobName = obj["job_name"]?.jsonPrimitive?.content ?: ""
        val resultObj = obj["result"]?.jsonObject
        val status = resultObj?.get("status")?.jsonPrimitive?.content ?: "unknown"
        val resultMap = resultObj?.mapValues { (_, v) ->
            when {
                v.jsonPrimitive.isString -> v.jsonPrimitive.content
                else -> v.toString()
            }
        } ?: emptyMap()

        return ChildResult(
            childId = childId,
            jobName = jobName,
            status = status,
            result = resultMap
        )
    }

    private fun buildSummary(children: List<ChildResult>, completed: Int, failed: Int): String {
        if (children.isEmpty()) return "No child tasks found."

        val sb = StringBuilder()
        sb.appendLine("SubAgent Results Summary:")
        sb.appendLine("  Total: ${children.size}, Completed: $completed, Failed: $failed")
        sb.appendLine()

        for (child in children) {
            val icon = if (child.status == "completed") "✓" else "✗"
            sb.appendLine("  $icon [${child.jobName}] (id=${child.childId}) → ${child.status}")
        }

        return sb.toString()
    }
}
