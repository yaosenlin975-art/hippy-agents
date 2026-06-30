package com.lin.hippyagent.core.deeplink

/**
 * 国内主流 App emoji 映射（17 个）。
 *
 * 未覆盖的 App 用通用 📱 emoji。
 */
object AppEmojiMapper {

    private val EMOJI_MAP: Map<String, String> = mapOf(
        "com.sankuai.meituan" to "🍜",          // 美团
        "com.taobao.taobao" to "🛒",             // 淘宝
        "com.xunmeng.pinduoduo" to "🍊",         // 拼多多
        "com.ss.android.ugc.aweme" to "🎵",      // 抖音
        "com.smile.gifmaker" to "🎬",            // 快手
        "com.zhihu.android" to "💡",             // 知乎
        "com.xingin.xhs" to "📕",                // 小红书
        "tv.danmaku.bili" to "📺",               // B站
        "com.autonavi.minimap" to "🗺️",          // 高德
        "com.tencent.qqmusic" to "🎶",           // QQ音乐
        "com.eg.android.AlipayGphone" to "💰",   // 支付宝
        "com.baidu.searchbox" to "🔍",           // 百度
        "com.dianping.v1" to "🍜",               // 点评
        "com.jingdong.app.mall" to "🛍️",         // 京东
        "com.tencent.mm" to "💬",                // 微信
        "com.alibaba.android.rimet" to "💼",     // 钉钉
        "com.ss.android.lark" to "🐦"            // 飞书
    )

    fun map(packageName: String): String = EMOJI_MAP[packageName] ?: "📱"

    fun mapAll(): Map<String, String> = EMOJI_MAP
}
