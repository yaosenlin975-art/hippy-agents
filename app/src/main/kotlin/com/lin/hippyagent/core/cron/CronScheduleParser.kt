package com.lin.hippyagent.core.cron

import java.util.Calendar
import java.util.TimeZone

class CronScheduleParser(
    private val timeZone: TimeZone = TimeZone.getDefault()
) {

    fun parse(expression: String): CronSchedule {
        val trimmed = expression.trim()
        val parts = trimmed.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        require(parts.size == 5 || parts.size == 6) {
            "Cron expression must have 5 or 6 fields, got ${parts.size}: $expression"
        }
        val secondField = if (parts.size == 6) parseField(parts[0], 0, 59) else null
        val base = if (parts.size == 6) 1 else 0
        val minute = parseField(parts[base], 0, 59)
        val hour = parseField(parts[base + 1], 0, 23)
        val dom = parseField(parts[base + 2], 1, 31)
        val month = parseField(parts[base + 3], 1, 12)
        val dow = parseField(parts[base + 4], 0, 6)
        return CronSchedule(
            raw = trimmed,
            fieldCount = parts.size,
            second = secondField,
            minute = minute,
            hour = hour,
            dayOfMonth = dom,
            month = month,
            dayOfWeek = dow
        )
    }

    fun nextFireTime(expression: String, from: Long = System.currentTimeMillis()): Long? {
        val schedule = runCatching { parse(expression) }.getOrNull() ?: return null
        return computeNextFire(schedule, from)
    }

    private fun parseField(field: String, min: Int, max: Int): IntRange {
        val union = mutableSetOf<Int>()
        field.split(COMMA_REGEX).forEach { token ->
            union += expandToken(token.trim(), min, max)
        }
        val sorted = union.filter { it in min..max }.sorted()
        if (sorted.isEmpty()) return min..min
        return sorted.first()..sorted.last()
    }

    private fun expandToken(token: String, min: Int, max: Int): Set<Int> {
        if (token.isEmpty()) return emptySet()
        val stepSplit = token.split(SLASH_REGEX, limit = 2)
        val basePart = stepSplit[0]
        val step = if (stepSplit.size == 2) stepSplit[1].toIntOrNull() ?: 1 else 1
        require(step > 0) { "Step must be positive: $token" }
        val range = if (basePart == "*") min..max else {
            val dashSplit = basePart.split(DASH_REGEX, limit = 2)
            when (dashSplit.size) {
                1 -> {
                    val v = dashSplit[0].toIntOrNull()
                    if (v == null) min..min else v..v
                }
                else -> {
                    val lo = dashSplit[0].toIntOrNull() ?: min
                    val hi = dashSplit[1].toIntOrNull() ?: max
                    lo..hi
                }
            }
        }
        val result = mutableSetOf<Int>()
        var v = range.first
        while (v <= range.last) {
            result += v
            v += step
        }
        return result
    }

    private fun computeNextFire(schedule: CronSchedule, from: Long): Long? {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = from
            add(Calendar.SECOND, 1)
            set(Calendar.MILLISECOND, 0)
        }
        val upperBound = from + FOUR_YEARS_MS
        var safety = 0
        while (cal.timeInMillis < upperBound && safety < MAX_ITERATIONS) {
            safety++
            if (schedule.second != null && !schedule.second.contains(cal.get(Calendar.SECOND))) {
                advanceSecond(cal)
                continue
            }
            if (!schedule.minute.contains(cal.get(Calendar.MINUTE))) {
                advanceMinute(cal)
                continue
            }
            if (!schedule.hour.contains(cal.get(Calendar.HOUR_OF_DAY))) {
                advanceHour(cal)
                continue
            }
            if (!schedule.month.contains(cal.get(Calendar.MONTH) + 1)) {
                advanceMonth(cal)
                continue
            }
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val dow = (cal.get(Calendar.DAY_OF_WEEK) - 1)
            val domOk = schedule.dayOfMonth.contains(dom)
            val dowOk = schedule.dayOfWeek.contains(dow)
            val dayMatch = if (schedule.dayOfMonth.first == 1 && schedule.dayOfMonth.last == 31
                && schedule.dayOfWeek.first == 0 && schedule.dayOfWeek.last == 6
            ) {
                true
            } else if (schedule.dayOfWeek.first == 0 && schedule.dayOfWeek.last == 6
                && !(schedule.dayOfMonth.first == 1 && schedule.dayOfMonth.last == 31)
            ) {
                dowOk
            } else if (schedule.dayOfMonth.first == 1 && schedule.dayOfMonth.last == 31
                && !(schedule.dayOfWeek.first == 0 && schedule.dayOfWeek.last == 6)
            ) {
                domOk
            } else {
                domOk || dowOk
            }
            if (!dayMatch) {
                advanceDay(cal)
                continue
            }
            return cal.timeInMillis
        }
        return null
    }

    private fun advanceSecond(cal: Calendar) {
        cal.set(Calendar.SECOND, cal.get(Calendar.SECOND) + 1)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun advanceMinute(cal: Calendar) {
        cal.add(Calendar.MINUTE, 1)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun advanceHour(cal: Calendar) {
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun advanceDay(cal: Calendar) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun advanceMonth(cal: Calendar) {
        cal.add(Calendar.MONTH, 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val COMMA_REGEX = Regex(",")
        private val SLASH_REGEX = Regex("/")
        private val DASH_REGEX = Regex("-")
        private const val FOUR_YEARS_MS = 4L * 365L * 24L * 60L * 60L * 1000L
        private const val MAX_ITERATIONS = 5_000_000
    }
}
