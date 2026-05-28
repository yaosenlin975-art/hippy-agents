package com.lin.hippyagent.core.agent.collaboration

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class BroadcastPreScorer(
    private val agentDescriptions: () -> Map<String, String>,
    private val triggerWords: () -> Map<String, List<String>>,
    private val pipeline: List<Scorer>,
    private val semanticScorer: SemanticScorer,
    private val threshold: Int = 7
) {

    suspend fun score(message: String, agentIds: List<String>): List<RelevanceScore> {
        val descriptions = agentDescriptions()
        return agentIds.mapNotNull { agentId ->
            val description = descriptions[agentId].orEmpty()
            var bestScore = 0
            var bestScorerName = ""
            var bestReason = ""
            for (scorer in pipeline) {
                val s = scorer.score(message, agentId, description)
                if (s > bestScore) {
                    bestScore = s
                    bestScorerName = scorer.name
                    bestReason = buildReason(scorer.name, s, agentId)
                }
                if (bestScore >= threshold) break
            }
            RelevanceScore(
                agentId = agentId,
                score = bestScore,
                reason = bestReason,
                scorerName = bestScorerName,
                threshold = threshold
            )
        }.sortedByDescending { it.score }
    }

    private fun buildReason(scorerName: String, score: Int, agentId: String): String {
        return "$scorerName: agent=$agentId score=$score"
    }
}

fun createBroadcastPreScorer(
    context: Context,
    appScope: CoroutineScope,
    agentDescriptions: () -> Map<String, String>,
    triggerWords: () -> Map<String, List<String>>
): BroadcastPreScorer {
    val modelFile = File(context.filesDir, "models/text2vec/model.onnx")
    val semanticScorer: SemanticScorer = if (modelFile.exists()) {
        Timber.d("ONNX model found, but ONNXSemanticScorer not implemented yet, using HybridSemanticScorer")
        HybridSemanticScorer(agentDescriptions())
    } else {
        HybridSemanticScorer(agentDescriptions())
    }
    val pipeline = mutableListOf<Scorer>(
        TriggerWordMatcher(triggerWords),
        DescriptionPhraseMatcher()
    )
    if (semanticScorer.isReady) {
        pipeline.add(semanticScorer)
    }
    if (modelFile.exists()) {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                semanticScorer.refreshEmbeddings(agentDescriptions())
            }.onFailure {
                Timber.e(it, "Failed to refresh embeddings")
            }
        }
    }
    return BroadcastPreScorer(
        agentDescriptions = agentDescriptions,
        triggerWords = triggerWords,
        pipeline = pipeline,
        semanticScorer = semanticScorer
    )
}
