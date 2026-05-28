package com.lin.hippyagent.core.cron

import java.util.Calendar

object NaturalLanguageCronParser {

    data class ParseResult(
        val cronExpression: String,
        val humanReadable: String,
        val confidence: Float,
        val isOneShot: Boolean = false,
        val delayMs: Long? = null
    )

    private val WEEKDAY_MAP = mapOf(
        "日" to 0, "天" to 0, "一" to 1, "二" to 2, "三" to 3,
        "四" to 4, "五" to 5, "六" to 6,
        "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3,
        "thu" to 4, "fri" to 5, "sat" to 6
    )

    private val DAILY_REGEX = Regex("""每天.*?(\d{1,2})[点时:：](\d{0,2})""")
    private val WEEKLY_REGEX = Regex("""每周([一二三四五六日天]).*?(\d{1,2})[点时:：](\d{0,2})""")
    private val WORKDAY_REGEX = Regex("""工作日.*?(\d{1,2})[点时:：](\d{0,2})""")
    private val INTERVAL_REGEX = Regex("""每隔(\d{1,2})(小时|分钟|分)""")
    private val MONTHLY_REGEX = Regex("""每月(\d{1,2})[号日].*?(\d{1,2})[点时:：](\d{0,2})""")
    private val TOMORROW_REGEX = Regex("""明[天早].*?(\d{1,2})[点时:：](\d{0,2})""")
    private val DELAY_REGEX = Regex("""(\d{1,3})(分钟|小时|秒)后""")
    private val HOUR_MINUTE_REGEX = Regex("""(\d{1,2})[点时:：](\d{0,2})""")

    fun parse(input: String): ParseResult? {
        val normalized = input.trim().lowercase()
            .replace("，", ",")
            .replace("。", ".")
            .replace("：", ":")
            .replace("半", "30")
            .replace("一刻", "15")
            .replace("三点", "3点")
            .replace("两点", "2点")
            .replace("十点", "10点")
            .replace("十一点", "11点")
            .replace("十二点", "12点")

        DAILY_REGEX.find(normalized)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            return ParseResult("0 $minute $hour * *", "每天 $hour:${minute.toString().padStart(2, '0')}", 0.95f)
        }

        WEEKLY_REGEX.find(normalized)?.let { match ->
            val dayStr = match.groupValues[1]
            val dow = WEEKDAY_MAP[dayStr] ?: return@let
            val hour = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            val dayNames = listOf("日", "一", "二", "三", "四", "五", "六")
            return ParseResult("0 $minute $hour * * $dow", "每周${dayNames[dow]} $hour:${minute.toString().padStart(2, '0')}", 0.9f)
        }

        WORKDAY_REGEX.find(normalized)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            return ParseResult("0 $minute $hour * * 1-5", "工作日 $hour:${minute.toString().padStart(2, '0')}", 0.9f)
        }

        INTERVAL_REGEX.find(normalized)?.let { match ->
            val interval = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2]
            return when (unit) {
                "小时" -> ParseResult("0 */$interval * * *", "每隔 $interval 小时", 0.9f)
                "分钟", "分" -> ParseResult("*/$interval * * * *", "每隔 $interval 分钟", 0.9f)
                else -> null
            }
        }

        MONTHLY_REGEX.find(normalized)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val hour = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: 0
            if (day !in 1..31 || hour !in 0..23 || minute !in 0..59) return@let
            return ParseResult("0 $minute $hour $day *", "每月${day}号 $hour:${minute.toString().padStart(2, '0')}", 0.85f)
        }

        TOMORROW_REGEX.find(normalized)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            val delayMs = cal.timeInMillis - System.currentTimeMillis()
            if (delayMs <= 0) return@let
            return ParseResult("0 $minute $hour * *", "明天 $hour:${minute.toString().padStart(2, '0')}", 0.8f, isOneShot = true, delayMs = delayMs)
        }

        DELAY_REGEX.find(normalized)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2]
            val delayMs = when (unit) {
                "秒" -> amount * 1000L
                "分钟" -> amount * 60 * 1000L
                "小时" -> amount * 3600 * 1000L
                else -> return@let
            }
            return ParseResult("", "${amount}${unit}后", 0.85f, isOneShot = true, delayMs = delayMs)
        }

        return null
    }
}
