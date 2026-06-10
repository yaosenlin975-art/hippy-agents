package com.lin.hippyagent.core.scheduler

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class NaturalLanguageTimeParser {

    fun parse(input: String): ScheduleParseResult {
        val original = input
        val normalized = input.trim().lowercase()
            .replace("，", ",")
            .replace("。", ".")
            .replace("：", ":")
            .replace("半", "30")
            .replace("一刻", "15")
            .replace("两点", "2点")
            .replace("三点", "3点")
            .replace("十点", "10点")
            .replace("十一点", "11点")
            .replace("十二点", "12点")

        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))

        DAILY_REGEX.find(normalized)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            val next = nextOccurrence(now, hour, minute)
            return buildDailyResult(next, hour, minute, original)
        }

        TOMORROW_REGEX.find(normalized)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            val next = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (next.timeInMillis <= now.timeInMillis) {
                next.add(Calendar.DAY_OF_MONTH, 1)
            }
            return buildOneShotResult(next, "明天 ${hour}:${minute.toString().padStart(2, '0')}", original, hour, minute)
        }

        WEEKLY_REGEX.find(normalized)?.let { match ->
            val dayStr = match.groupValues[1]
            val dow = WEEKDAY_MAP[dayStr] ?: return@let
            val hour = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            val dayNames = listOf("日", "一", "二", "三", "四", "五", "六")
            val next = nextWeekday(now, dow, hour, minute)
            val human = "每周${dayNames[dow]} ${hour}:${minute.toString().padStart(2, '0')}"
            return ScheduleParseResult(
                success = true,
                cron = "0 $minute $hour * * $dow",
                isoTimestamp = formatIso(next.time),
                humanReadable = human,
                nextFireTime = next.timeInMillis,
                parseMethod = ParseMethod.RULE,
                rawText = original
            )
        }

        NEXT_WEEKDAY_REGEX.find(normalized)?.let { match ->
            val dayStr = match.groupValues[1]
            val targetDow = WEEKDAY_MAP[dayStr] ?: return@let
            val hour = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return@let
            val dayNames = listOf("日", "一", "二", "三", "四", "五", "六")
            val thisWeek = nextWeekday(now, targetDow, hour, minute)
            val nextWeek = (thisWeek.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, 1) }
            val humanBase = "${dayNames[targetDow]} ${hour}:${minute.toString().padStart(2, '0')}"
            val candidates = listOf(
                buildWeeklyCandidate(thisWeek, targetDow, hour, minute, "本周$humanBase"),
                buildWeeklyCandidate(nextWeek, targetDow, hour, minute, "下周$humanBase")
            )
            return ScheduleParseResult(
                success = true,
                cron = candidates.first().cron,
                isoTimestamp = candidates.first().isoTimestamp,
                humanReadable = "请选择触发时间",
                nextFireTime = candidates.first().nextFireTime,
                ambiguityCandidates = candidates,
                parseMethod = ParseMethod.RULE,
                rawText = original
            )
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
            val fireAt = now.timeInMillis + delayMs
            return ScheduleParseResult(
                success = true,
                cron = "",
                isoTimestamp = formatIso(Date(fireAt)),
                humanReadable = "${amount}${unit}后",
                nextFireTime = fireAt,
                parseMethod = ParseMethod.RULE,
                isOneShot = true,
                delayMs = delayMs,
                rawText = original
            )
        }

        return ScheduleParseResult(
            success = false,
            errorMessage = "规则解析失败，请使用 LLM 或手动输入",
            parseMethod = ParseMethod.RULE,
            rawText = original
        )
    }

    private fun buildDailyResult(next: Calendar, hour: Int, minute: Int, original: String): ScheduleParseResult {
        return ScheduleParseResult(
            success = true,
            cron = "0 $minute $hour * *",
            isoTimestamp = formatIso(next.time),
            humanReadable = "每天 ${hour}:${minute.toString().padStart(2, '0')}",
            nextFireTime = next.timeInMillis,
            parseMethod = ParseMethod.RULE,
            rawText = original
        )
    }

    private fun buildOneShotResult(next: Calendar, label: String, original: String, hour: Int, minute: Int): ScheduleParseResult {
        return ScheduleParseResult(
            success = true,
            cron = "0 $minute $hour * *",
            isoTimestamp = formatIso(next.time),
            humanReadable = label,
            nextFireTime = next.timeInMillis,
            parseMethod = ParseMethod.RULE,
            isOneShot = true,
            delayMs = next.timeInMillis - System.currentTimeMillis(),
            rawText = original
        )
    }

    private fun buildWeeklyCandidate(cal: Calendar, dow: Int, hour: Int, minute: Int, label: String): ScheduleParseResult {
        return ScheduleParseResult(
            success = true,
            cron = "0 $minute $hour * * $dow",
            isoTimestamp = formatIso(cal.time),
            humanReadable = label,
            nextFireTime = cal.timeInMillis,
            parseMethod = ParseMethod.RULE,
            rawText = label
        )
    }

    private fun nextOccurrence(now: Calendar, hour: Int, minute: Int): Calendar {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target
    }

    private fun nextWeekday(now: Calendar, targetDow: Int, hour: Int, minute: Int): Calendar {
        val currentDow = now.get(Calendar.DAY_OF_WEEK) - 1
        var delta = (targetDow - currentDow + 7) % 7
        if (delta == 0) {
            val todayTarget = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (todayTarget.timeInMillis <= now.timeInMillis) {
                delta = 7
            }
        }
        return (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, delta)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun formatIso(date: Date): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CHINA)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return fmt.format(date)
    }

    companion object {
        private val WEEKDAY_MAP = mapOf(
            "日" to 0, "天" to 0, "一" to 1, "二" to 2, "三" to 3,
            "四" to 4, "五" to 5, "六" to 6,
            "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3,
            "thu" to 4, "fri" to 5, "sat" to 6
        )
        private val DAILY_REGEX = Regex("""每天.*?(\d{1,2})[点时:：](\d{0,2})""")
        private val TOMORROW_REGEX = Regex("""明[天早].*?(\d{1,2})[点时:：](\d{0,2})""")
        private val WEEKLY_REGEX = Regex("""每周([一二三四五六日天]).*?(\d{1,2})[点时:：](\d{0,2})""")
        private val NEXT_WEEKDAY_REGEX = Regex("""下(周)?([一二三四五六日天]).*?(\d{1,2})[点时:：](\d{0,2})""")
        private val DELAY_REGEX = Regex("""(\d{1,3})(分钟|小时|秒)后""")
    }
}
