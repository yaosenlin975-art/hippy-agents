package com.lin.hippyagent.core.accessibility

import timber.log.Timber

val AUTO_DISMISS_TARGETS = listOf("关闭广告", "跳过广告", "关闭", "跳过", "不感兴趣", "稍后", "×", "✕")

class AdUiGuard {

    companion object {
        private val AD_SDK_CLASS_NAMES = setOf(
            "AdView", "BannerView", "NativeAdView", "InterstitialAd",
            "RewardedAdView", "SplashAdView", "FeedAdView",
            "TTAdSdk", "TTFeedView", "TTBannerView", "TTInteractionView",
            "Banner", "AdContainer", "AdLayout", "AdFrame",
            "UnifiedAdView", "MediaView", "AdImageView"
        )

        private val AD_COPY_PATTERNS = listOf(
            "广告", "AD", "ad", "Sponsored", "赞助", "推广",
            "立即下载", "立即安装", "了解更多", "立即体验", "去看看", "立即打开",
            "Download", "Install", "Learn more", "Shop now"
        )

        private val CLOSE_BUTTON_PATTERNS = listOf("关闭", "×", "✕", "close", "dismiss", "skip", "跳过", "不再显示")

        private val HINT_BUTTON_PATTERNS = listOf(
            "查看提示", "提示", "help", "hint", "题解", "解析"
        )
    }

    fun isLikelyAdvertisement(node: SerializedNode): Boolean {
        val isAdSdkClass = AD_SDK_CLASS_NAMES.any { node.type.contains(it, ignoreCase = true) }
        if (isAdSdkClass && !isCloseButton(node)) return true

        val text = (node.text ?: node.content_desc ?: "").lowercase()
        if (text.isNotBlank()) {
            val hasAdCopy = AD_COPY_PATTERNS.any { text.contains(it.lowercase()) }
            if (hasAdCopy && !isCloseButton(node)) return true
        }

        return false
    }

    private fun isCloseButton(node: SerializedNode): Boolean {
        val text = (node.text ?: node.content_desc ?: "").trim()
        if (text.isBlank()) return false
        return CLOSE_BUTTON_PATTERNS.any { text.equals(it, ignoreCase = true) }
    }

    fun isHintButtonGuard(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        return HINT_BUTTON_PATTERNS.any { trimmed.contains(it.lowercase()) }
    }

    fun tryAutoDismissObstruction(
        nodes: List<SerializedNode>,
        engine: DualTrackDecisionEngine
    ): List<DualTrackResult> {
        val results = mutableListOf<DualTrackResult>()
        val flatNodes = flattenNodes(nodes)

        for (target in AUTO_DISMISS_TARGETS) {
            val match = flatNodes.firstOrNull { node ->
                val text = (node.text ?: node.content_desc ?: "").trim()
                text.equals(target, ignoreCase = true) && node.clickable
            } ?: continue

            val coords = parseBoundsCenter(match.bounds) ?: continue
            results.add(DualTrackResult(
                x = coords.first,
                y = coords.second,
                confidence = 0.9f,
                source = "auto_dismiss",
                nodeRef = match.view_id
            ))
            Timber.d("Auto-dismiss target found: '%s' at (%.0f, %.0f)", target, coords.first, coords.second)
            break
        }

        return results
    }

    fun looksLikeAdvertisementScreen(nodes: List<SerializedNode>): Boolean {
        val flatNodes = flattenNodes(nodes)
        var score = 0
        val seenSdkClasses = mutableSetOf<String>()

        for (node in flatNodes) {
            AD_SDK_CLASS_NAMES.forEach { sdk ->
                if (node.type.contains(sdk, ignoreCase = true) && sdk !in seenSdkClasses) {
                    seenSdkClasses.add(sdk)
                    score++
                }
            }

            val text = (node.text ?: node.content_desc ?: "").lowercase()
            if (text.isNotBlank()) {
                AD_COPY_PATTERNS.forEach { pattern ->
                    if (text.contains(pattern.lowercase())) {
                        score++
                        return@forEach
                    }
                }
            }

            if (score >= 2) return true
        }

        return false
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
        val regex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        val match = regex.find(bounds) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        return ((x1.toInt() + x2.toInt()) / 2f) to ((y1.toInt() + y2.toInt()) / 2f)
    }
}
