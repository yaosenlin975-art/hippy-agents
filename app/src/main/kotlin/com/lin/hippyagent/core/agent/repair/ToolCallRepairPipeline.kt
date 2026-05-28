package com.lin.hippyagent.core.agent.repair

import com.lin.hippyagent.core.model.ToolCallInfo
import timber.log.Timber

data class RepairPipelineResult(
    val scavenged: Int,
    val truncationsFixed: Int,
    val stormsBroken: Int,
    val repairedToolCalls: List<ToolCallInfo>
)

class ToolCallRepairPipeline {

    private val scavengeRepair = ScavengeRepair()
    private val truncationRepair = TruncationRepair()
    private val stormBreaker = StormBreaker()

    fun repair(
        toolCalls: List<ToolCallInfo>,
        content: String,
        reasoningContent: String?,
        allowedToolNames: Set<String>
    ): RepairPipelineResult {
        val scavengeReport = scavengeRepair.scavenge(
            toolCalls, content, reasoningContent, allowedToolNames
        )
        val scavengedCalls = scavengeReport.repairedCalls.map { it.toToolCallInfo() }
        val allCalls = toolCalls + scavengedCalls

        val (truncationFixedCalls, truncationReport) = truncationRepair.repair(allCalls)

        val stormResult = stormBreaker.check(truncationFixedCalls)
        val suppressedSet = stormResult.suppressedCalls.toSet()
        val finalCalls = truncationFixedCalls.filterIndexed { index, _ -> index !in suppressedSet }

        Timber.d(
            "ToolCallRepairPipeline: scavenged=${scavengeReport.scavenged}, " +
                    "truncationsFixed=${truncationReport.truncationsFixed}, " +
                    "stormsBroken=${stormResult.stormsBroken}, " +
                    "finalCalls=${finalCalls.size}"
        )

        return RepairPipelineResult(
            scavenged = scavengeReport.scavenged,
            truncationsFixed = truncationReport.truncationsFixed,
            stormsBroken = stormResult.stormsBroken,
            repairedToolCalls = finalCalls
        )
    }

    fun resetStorm() {
        stormBreaker.resetStorm()
    }
}
