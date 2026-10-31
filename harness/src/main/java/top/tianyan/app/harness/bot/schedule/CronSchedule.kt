package top.tianyan.app.harness.bot.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 标准 5 位 Cron：分 时 日 月 周；周字段 0/7 = 周日。 */
data class CronSchedule(
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    val daysOfWeek: Set<Int>,
    private val dayOfMonthAny: Boolean,
    private val dayOfWeekAny: Boolean,
) {
    fun matches(time: LocalDateTime): Boolean {
        if (time.minute !in minutes || time.hour !in hours || time.monthValue !in months) return false
        val dom = time.dayOfMonth in daysOfMonth
        val dow = (time.dayOfWeek.value % 7) in daysOfWeek
        return when {
            dayOfMonthAny && dayOfWeekAny -> true
            dayOfMonthAny -> dow
            dayOfWeekAny -> dom
            else -> dom || dow
        }
    }

    /** 严格晚于 [now] 的下一个触发时刻（毫秒）。两年内无匹配返回 null。 */
    fun nextAfter(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        var time = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
            .withSecond(0).withNano(0).plusMinutes(1)
        val limit = time.plusYears(MAX_YEARS)
        while (time.isBefore(limit)) {
            if (time.monthValue !in months) {
                time = time.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0)
                continue
            }
            if (!dayMatches(time)) {
                time = time.plusDays(1).withHour(0).withMinute(0)
                continue
            }
            if (time.hour !in hours) {
                time = time.plusHours(1).withMinute(0)
                continue
            }
            if (time.minute !in minutes) {
                time = time.plusMinutes(1)
                continue
            }
            val fire = fireInstant(time, zone, now)
            if (fire != null) return fire
            // 本地时刻在回拨重叠里只有较早的瞬时，且已早于 now：跳过该本地分钟继续找。
            time = time.plusMinutes(1)
        }
        return null
    }

    /**
     * 把一个「本地时刻已匹配」的候选解析成真正晚于 [after] 的瞬时。
     *
     * 夏令时回拨时同一本地时刻对应两个瞬时（如 Europe/Berlin 2026-10-25 的 02:45 既是
     * 00:45Z 也是 01:45Z），[LocalDateTime.atZone] 只取较早的那个；若 now 已越过较早瞬时，
     * 直接返回就会得到早于 now 的时刻，违反「严格晚于 now」的契约。这里枚举全部合法偏移，
     * 取仍晚于 [after] 的最小瞬时。春季跳变的空洞时刻没有合法偏移，按既有行为向后归一并
     * 以归一时刻判定。
     */
    private fun fireInstant(time: LocalDateTime, zone: ZoneId, after: Long): Long? {
        val offsets = zone.rules.getValidOffsets(time)
        if (offsets.isEmpty()) {
            return time.atZone(zone).toInstant().toEpochMilli().takeIf { it > after }
        }
        return offsets.asSequence()
            .map { time.toInstant(it).toEpochMilli() }
            .filter { it > after }
            .minOrNull()
    }

    private fun dayMatches(time: LocalDateTime): Boolean {
        val dom = time.dayOfMonth in daysOfMonth
        val dow = (time.dayOfWeek.value % 7) in daysOfWeek
        return when {
            dayOfMonthAny && dayOfWeekAny -> true
            dayOfMonthAny -> dow
            dayOfWeekAny -> dom
            else -> dom || dow
        }
    }

    companion object {
        private const val MAX_YEARS = 2L

        fun validate(expression: String): String? = runCatching { parse(expression) }.exceptionOrNull()?.message

        fun parse(expression: String): CronSchedule {
            val parts = expression.trim().split(Regex("\\s+"))
            require(parts.size == 5) { "Cron 必须为 5 段：分 时 日 月 周" }
            val minute = parseField(parts[0], 0, 59, "分钟")
            val hour = parseField(parts[1], 0, 23, "小时")
            val dom = parseField(parts[2], 1, 31, "日")
            val month = parseField(parts[3], 1, 12, "月")
            val dow = parseField(parts[4], 0, 7, "周").let { field ->
                field.copy(values = field.values.map { if (it == 7) 0 else it }.toSet())
            }
            return CronSchedule(
                minutes = minute.values,
                hours = hour.values,
                daysOfMonth = dom.values,
                months = month.values,
                daysOfWeek = dow.values,
                dayOfMonthAny = parts[2] == "*" || parts[2] == "?",
                dayOfWeekAny = parts[4] == "*" || parts[4] == "?",
            )
        }

        private fun parseField(raw: String, min: Int, max: Int, label: String): Field {
            val text = raw.trim()
            require(text.isNotEmpty()) { "$label 字段为空" }
            if (text == "*" || text == "?") return Field((min..max).toSet())
            val values = sortedSetOf<Int>()
            text.split(',').forEach { token ->
                require(token.isNotBlank()) { "$label 字段含空项" }
                val rangePart = token.substringBefore('/')
                val step = token.substringAfter('/', "").takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
                require(step > 0) { "$label 步长必须大于 0" }
                val range = when {
                    rangePart == "*" || rangePart == "?" -> min..max
                    '-' in rangePart -> {
                        val start = rangePart.substringBefore('-').toIntOrNull()
                            ?: throw IllegalArgumentException("$label 范围无效: $token")
                        val end = rangePart.substringAfter('-').toIntOrNull()
                            ?: throw IllegalArgumentException("$label 范围无效: $token")
                        require(start <= end) { "$label 范围起点大于终点: $token" }
                        start..end
                    }
                    else -> {
                        val value = rangePart.toIntOrNull()
                            ?: throw IllegalArgumentException("$label 取值无效: $token")
                        value..value
                    }
                }
                require(range.first >= min && range.last <= max) { "$label 超出 $min-$max: $token" }
                range.step(step).forEach { values += it }
            }
            require(values.isNotEmpty()) { "$label 字段没有有效取值" }
            return Field(values)
        }

        private data class Field(val values: Set<Int>)
    }
}
