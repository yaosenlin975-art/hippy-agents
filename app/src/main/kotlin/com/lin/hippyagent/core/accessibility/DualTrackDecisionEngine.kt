package com.lin.hippyagent.core.accessibility

import kotlinx.serialization.Serializable
import timber.log.Timber
import java.security.MessageDigest
import kotlin.math.sqrt

@Serializable
data class SemanticMapping(
    val targetNorm: String,
    val packageName: String,
    val screenSignature: String,
    val resourceId: String? = null,
    val textHint: String? = null,
    val x: Float,
    val y: Float,
    val hitCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class DualTrackResult(
    val x: Float,
    val y: Float,
    val confidence: Float,
    val source: String,
    val nodeRef: String? = null
)

class DualTrackDecisionEngine(
    private val vlmProvider: VlmProvider? = null,
    private val screenshotCapturer: ScreenshotCapturer? = null
) {
    companion object {
        private const val MEMORY_MAX_SIZE = 220
        private const val MEMORY_TTL_MS = 12 * 60 * 60 * 1000L
        private const val VLM_CONFIDENCE_THRESHOLD = 0.78f
        private const val SURFACE_VIEW_CAP = 0.35f
        private const val FUSION_RADIUS_MIN = 88f
        private const val FUSION_RADIUS_MAX = 240f
        private const val WARMUP_CACHE_SIZE = 8
        private const val WARMUP_CACHE_TTL_MS = 15_000L
        private const val WARMUP_TARGET_MAX = 14
        private const val SCORE_EXACT = 0.95f
        private const val SCORE_SUBSTRING = 0.84f
        private const val SCORE_OVERLAP = 0.70f
        private const val MEMORY_HIT_THRESHOLD = 3
        private const val MEMORY_WRITE_CONFIDENCE = 0.6f
        private const val AVOID_COORDINATE_RADIUS = 50f
        private const val AVOID_CONFIDENCE_PENALTY = 0.5f
        private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }

    private val semanticMemory = ArrayDeque<SemanticMapping>()
    private val warmupCache = LinkedHashMap<String, VlmWarmupEntry>(WARMUP_CACHE_SIZE, 0.75f, true)
    private var lastUiFingerprint: String? = null
    private var lastDominantPackage: String? = null

    private data class VlmWarmupEntry(
        val result: DualTrackResult,
        val timestamp: Long
    )

    fun querySemanticMemory(targetNorm: String, packageName: String): SemanticMapping? {
        val now = System.currentTimeMillis()
        return semanticMemory
            .filter { it.targetNorm == targetNorm && it.packageName == packageName && (now - it.updatedAt) < MEMORY_TTL_MS }
            .maxByOrNull { it.hitCount }
    }

    fun scoreUiNodes(targetDescription: String, nodes: List<SerializedNode>): Pair<SerializedNode, Float>? {
        val norm = normalizeTarget(targetDescription)
        var bestNode: SerializedNode? = null
        var bestScore = 0f

        for (node in flattenNodes(nodes)) {
            if (!node.clickable && !node.scrollable && !node.editable) continue
            val score = computeNodeScore(norm, node)
            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }
        }

        return bestNode?.let { it to bestScore }
    }

    private fun computeNodeScore(targetNorm: String, node: SerializedNode): Float {
        val isSurfaceView = node.type.contains("SurfaceView", ignoreCase = true)
        val textNorm = node.text?.lowercase()?.trim()
        val descNorm = node.content_desc?.lowercase()?.trim()
        val viewIdNorm = node.view_id?.lowercase()?.substringAfterLast("/")

        val score = when {
            textNorm == targetNorm || descNorm == targetNorm || viewIdNorm == targetNorm -> SCORE_EXACT
            textNorm?.contains(targetNorm) == true || descNorm?.contains(targetNorm) == true -> SCORE_SUBSTRING
            textNorm != null && hasOverlap(targetNorm, textNorm) -> SCORE_OVERLAP
            descNorm != null && hasOverlap(targetNorm, descNorm) -> SCORE_OVERLAP
            else -> 0f
        }

        return if (isSurfaceView) minOf(score, SURFACE_VIEW_CAP) else score
    }

    private fun hasOverlap(a: String, b: String): Boolean {
        val aWords = a.split(Regex("\\s+")).toSet()
        val bWords = b.split(Regex("\\s+")).toSet()
        return aWords.intersect(bWords).isNotEmpty()
    }

    private fun normalizeTarget(target: String): String = target.lowercase().trim()

    suspend fun decide(
        targetDescription: String,
        nodes: List<SerializedNode>,
        packageName: String,
        screenSignature: String,
        preferVisual: Boolean = false,
        avoidCoordinates: List<Pair<Float, Float>> = emptyList()
    ): DualTrackResult {
        val norm = normalizeTarget(targetDescription)
        val memoryHit = querySemanticMemory(norm, packageName)
        val uiResult = scoreUiNodes(targetDescription, nodes)
        val uiConfidence = uiResult?.second ?: 0f

        val shouldUseVlm = preferVisual || uiConfidence < VLM_CONFIDENCE_THRESHOLD || isSurfaceViewDominant(nodes)
        val vlmResult = if (shouldUseVlm) queryVlmWithCache(targetDescription, avoidCoordinates) else null

        val result = fuseDecision(memoryHit, uiResult, vlmResult, preferVisual)

        if (result.confidence > MEMORY_WRITE_CONFIDENCE) {
            writeToMemory(result, targetDescription, packageName, screenSignature)
        }

        Timber.d("DualTrack decide: target=%s, source=%s, conf=%.2f", targetDescription, result.source, result.confidence)
        return result
    }

    private fun isSurfaceViewDominant(nodes: List<SerializedNode>): Boolean {
        val flat = flattenNodes(nodes)
        val surfaceCount = flat.count { it.type.contains("SurfaceView", ignoreCase = true) }
        return surfaceCount > 0 && flat.isNotEmpty() && surfaceCount.toFloat() / flat.size > 0.3f
    }

    private suspend fun queryVlmWithCache(
        targetDescription: String,
        avoidCoordinates: List<Pair<Float, Float>>
    ): DualTrackResult? {
        val cacheKey = normalizeTarget(targetDescription)
        val cached = warmupCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < WARMUP_CACHE_TTL_MS) {
            return applyAvoidCoordinates(cached.result, avoidCoordinates)
        }

        if (vlmProvider == null || screenshotCapturer == null) return null
        val service = PhoneControlAccessibilityService.instance ?: return null
        val screenshot = screenshotCapturer.captureForVlm(service) ?: return null

        return try {
            val vlmResult = vlmProvider.analyze(screenshot.first, screenshot.second)
            val norm = normalizeTarget(targetDescription)
            val element = vlmResult.elements.firstOrNull {
                it.description?.lowercase()?.contains(norm) == true || it.text?.lowercase()?.contains(norm) == true
            }
            element?.let { el ->
                parseBoundsCenter(el.bounds)?.let { (x, y) ->
                    applyAvoidCoordinates(
                        DualTrackResult(x, y, vlmResult.confidence, "vlm"),
                        avoidCoordinates
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "VLM query failed in DualTrack")
            null
        }
    }

    private fun applyAvoidCoordinates(result: DualTrackResult, avoid: List<Pair<Float, Float>>): DualTrackResult {
        for ((ax, ay) in avoid) {
            val dist = sqrt((result.x - ax) * (result.x - ax) + (result.y - ay) * (result.y - ay))
            if (dist < AVOID_COORDINATE_RADIUS) {
                return result.copy(confidence = result.confidence * AVOID_CONFIDENCE_PENALTY)
            }
        }
        return result
    }

    private fun fuseDecision(
        memoryHit: SemanticMapping?,
        uiResult: Pair<SerializedNode, Float>?,
        vlmResult: DualTrackResult?,
        preferVisual: Boolean
    ): DualTrackResult {
        val uiCoords = uiResult?.first?.bounds?.let { parseBoundsCenter(it) }
        val uiConfidence = uiResult?.second ?: 0f
        val uiNodeRef = uiResult?.first?.view_id
        val uiResultFinal = uiCoords?.let { DualTrackResult(it.first, it.second, uiConfidence, "ui", uiNodeRef) }

        if (memoryHit != null && memoryHit.hitCount >= MEMORY_HIT_THRESHOLD) {
            val memResult = DualTrackResult(memoryHit.x, memoryHit.y, 0.85f, "memory")
            if (vlmResult != null) {
                return fuseVlmWithUi(vlmResult, uiResultFinal, preferVisual)
            }
            if (uiResultFinal != null && uiConfidence >= 0.7f) return uiResultFinal
            return memResult
        }

        if (vlmResult == null && uiResultFinal == null) {
            return memoryHit?.let { DualTrackResult(it.x, it.y, 0.5f, "memory") }
                ?: DualTrackResult(0f, 0f, 0f, "none")
        }

        if (vlmResult == null) return uiResultFinal!!
        if (uiResultFinal == null) return vlmResult

        return fuseVlmWithUi(vlmResult, uiResultFinal, preferVisual)
    }

    private fun fuseVlmWithUi(
        vlmResult: DualTrackResult,
        uiResult: DualTrackResult?,
        preferVisual: Boolean
    ): DualTrackResult {
        if (uiResult == null) return vlmResult

        val chooseVlm = if (preferVisual) {
            vlmResult.confidence >= uiResult.confidence - 0.08f
        } else {
            vlmResult.confidence >= uiResult.confidence + 0.05f || uiResult.confidence < 0.55f
        }

        if (chooseVlm) {
            val dist = computeDistance(vlmResult, uiResult)
            if (dist in FUSION_RADIUS_MIN..FUSION_RADIUS_MAX) {
                return uiResult.copy(confidence = uiResult.confidence + 0.10f, source = "fused")
            }
            return vlmResult
        }

        return uiResult
    }

    private fun computeDistance(a: DualTrackResult, b: DualTrackResult): Float {
        return sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
    }

    private fun writeToMemory(result: DualTrackResult, target: String, packageName: String, screenSignature: String) {
        val norm = normalizeTarget(target)
        val existing = semanticMemory.find { it.targetNorm == norm && it.packageName == packageName }
        if (existing != null) {
            semanticMemory.remove(existing)
            semanticMemory.addLast(existing.copy(
                x = result.x,
                y = result.y,
                hitCount = existing.hitCount + 1,
                updatedAt = System.currentTimeMillis()
            ))
        } else {
            if (semanticMemory.size >= MEMORY_MAX_SIZE) semanticMemory.removeFirst()
            semanticMemory.addLast(SemanticMapping(
                targetNorm = norm,
                packageName = packageName,
                screenSignature = screenSignature,
                resourceId = null,
                textHint = target,
                x = result.x,
                y = result.y,
                hitCount = 1,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    suspend fun warmupOnSnapshotAsync(nodes: List<SerializedNode>, packageName: String) {
        if (vlmProvider == null || screenshotCapturer == null) return

        val highValue = flattenNodes(nodes)
            .filter { it.clickable || it.scrollable || it.editable }
            .sortedWith(compareByDescending<SerializedNode> { it.clickable }.thenByDescending { it.text != null })
            .take(WARMUP_TARGET_MAX)

        if (highValue.isEmpty()) return

        val service = PhoneControlAccessibilityService.instance ?: return
        val screenshot = screenshotCapturer.captureForVlm(service) ?: return

        try {
            val vlmResult = vlmProvider.analyze(screenshot.first, screenshot.second)
            for (element in vlmResult.elements) {
                val coords = parseBoundsCenter(element.bounds) ?: continue
                val key = element.description?.lowercase()?.trim() ?: element.text?.lowercase()?.trim() ?: continue
                warmupCache[key] = VlmWarmupEntry(
                    result = DualTrackResult(coords.first, coords.second, vlmResult.confidence, "vlm_warmup"),
                    timestamp = System.currentTimeMillis()
                )
                if (warmupCache.size > WARMUP_CACHE_SIZE) {
                    val iter = warmupCache.entries.iterator()
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }
            Timber.d("VLM warmup: cached %d targets for %s", warmupCache.size, packageName)
        } catch (e: Exception) {
            Timber.w(e, "VLM warmup failed")
        }
    }

    fun hasUiChanged(nodes: List<SerializedNode>, packageName: String): Boolean {
        val currentFingerprint = computeFingerprint(nodes)
        val currentDominant = packageName
        val changed = currentFingerprint != lastUiFingerprint || currentDominant != lastDominantPackage
        lastUiFingerprint = currentFingerprint
        lastDominantPackage = currentDominant
        return changed
    }

    private fun computeFingerprint(nodes: List<SerializedNode>): String {
        val sb = StringBuilder()
        for (node in flattenNodes(nodes).take(50)) {
            sb.append(node.type)
            sb.append(node.text ?: "")
            sb.append(node.view_id ?: "")
            sb.append(node.bounds ?: "")
        }
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(sb.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun flattenNodes(nodes: List<SerializedNode>): List<SerializedNode> {
        val result = mutableListOf<SerializedNode>()
        for (node in nodes) {
            flattenNodeRecursive(node, result)
        }
        return result
    }

    private fun flattenNodeRecursive(node: SerializedNode, acc: MutableList<SerializedNode>) {
        acc.add(node)
        node.children?.forEach { flattenNodeRecursive(it, acc) }
    }

    private fun parseBoundsCenter(bounds: String?): Pair<Float, Float>? {
        if (bounds == null) return null
        val match = BOUNDS_REGEX.find(bounds) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        return ((x1.toInt() + x2.toInt()) / 2f) to ((y1.toInt() + y2.toInt()) / 2f)
    }
}
