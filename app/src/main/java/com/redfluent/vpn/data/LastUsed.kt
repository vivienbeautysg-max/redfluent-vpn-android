package com.redfluent.vpn.data

/**
 * "Last used" 的时间解析与相对文案（纯逻辑，不依赖 java.time——minSdk 24 没有它）。
 */

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60 * MS_PER_SECOND
private const val MS_PER_HOUR = 60 * MS_PER_MINUTE
private const val MS_PER_DAY = 24 * MS_PER_HOUR

/** 1970-01-01 起算的天数（公历）。算法：Howard Hinnant 的 days_from_civil。 */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097 + doe - 719_468
}

/** daysFromCivil 的反函数，返回 (年, 月, 日)。 */
internal fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = (z - era * 146_097).toInt()
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = (yoe + era * 400).toInt() + if (month <= 2) 1 else 0
    return Triple(year, month, day)
}

/**
 * 解析后端给的时间 → UTC 毫秒；解析不了 → null（界面当作 "Never"，不显示乱码）。
 * 接受：2026-10-22T10:30:00.000Z、2026-10-22T10:30:00Z、2026-10-22T10:30:00+08:00、
 * SQLite 的 2026-10-22 10:30:00（没有时区标记，按 UTC）、只有日期的 2026-10-22（当天 0 点 UTC）。
 */
fun parseInstantMillis(text: String?): Long? {
    val s = text?.trim().orEmpty()
    if (s.length < 10 || s[4] != '-' || s[7] != '-') return null

    fun digits(from: Int, to: Int): Int? {
        if (to > s.length) return null
        val part = s.substring(from, to)
        return if (part.all { it in '0'..'9' }) part.toInt() else null
    }

    val year = digits(0, 4) ?: return null
    val month = digits(5, 7)?.takeIf { it in 1..12 } ?: return null
    val day = digits(8, 10)?.takeIf { it in 1..31 } ?: return null
    val days = daysFromCivil(year, month, day)
    if (civilFromDays(days) != Triple(year, month, day)) return null // 例如 2026-02-31
    var millis = days * MS_PER_DAY
    if (s.length == 10) return millis

    if (s[10] != 'T' && s[10] != ' ') return null
    val hour = digits(11, 13)?.takeIf { it in 0..23 } ?: return null
    if (s.getOrNull(13) != ':') return null
    val minute = digits(14, 16)?.takeIf { it in 0..59 } ?: return null
    var i = 16
    var second = 0
    var fraction = 0L
    if (s.getOrNull(i) == ':') {
        second = digits(i + 1, i + 3)?.takeIf { it in 0..59 } ?: return null
        i += 3
        if (s.getOrNull(i) == '.') {
            var j = i + 1
            while (j < s.length && s[j] in '0'..'9') j++
            if (j == i + 1) return null
            fraction = s.substring(i + 1, minOf(j, i + 4)).padEnd(3, '0').toLong()
            i = j
        }
    }
    millis += ((hour * 60L + minute) * 60 + second) * MS_PER_SECOND + fraction

    if (i == s.length) return millis // 没有时区标记：按 UTC
    return when (s[i]) {
        'Z', 'z' -> if (i + 1 == s.length) millis else null
        '+', '-' -> {
            val sign = if (s[i] == '+') 1 else -1
            val oh = digits(i + 1, i + 3)?.takeIf { it in 0..23 } ?: return null
            var k = i + 3
            if (s.getOrNull(k) == ':') k++
            val om = digits(k, k + 2)?.takeIf { it in 0..59 } ?: return null
            if (k + 2 != s.length) return null
            millis - sign * (oh * 60L + om) * MS_PER_MINUTE
        }
        else -> null
    }
}

/** 多久以前："just now" / "5m ago" / "2h ago" / "3d ago"；超过 30 天显示日期 "3 Nov 2026"。时钟比服务器快（负数）也按 "just now"。 */
fun relativeAgo(lastMs: Long, nowMs: Long): String {
    val delta = nowMs - lastMs
    return when {
        delta < MS_PER_MINUTE -> "just now"
        delta < MS_PER_HOUR -> "${delta / MS_PER_MINUTE}m ago"
        delta < MS_PER_DAY -> "${delta / MS_PER_HOUR}h ago"
        delta < 30 * MS_PER_DAY -> "${delta / MS_PER_DAY}d ago"
        else -> {
            val (y, m, d) = civilFromDays(Math.floorDiv(lastMs, MS_PER_DAY))
            "$d ${MONTH_ABBR[m - 1]} $y"
        }
    }
}

private val MONTH_ABBR = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** 用户行 / 设备行的 "Last used" 文案：从来没用过 → "Never"，否则 "Last used 2h ago"。 */
fun lastUsedText(lastMs: Long?, nowMs: Long): String =
    if (lastMs == null) "Never" else "Last used ${relativeAgo(lastMs, nowMs)}"
