package ua.madeisy.iot.esp

import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule

/**
 * Часовий пояс телефона у форматі POSIX TZ (його розуміє плата):
 * Europe/Kyiv → "STD-2DST,M3.5.0/3,M10.5.0/4".
 */
object PosixTz {
    fun current(): String = of(ZoneId.systemDefault())

    fun of(zone: ZoneId): String {
        val rules = zone.rules
        val tr = rules.transitionRules
        if (tr.size != 2) {
            // без переходу на літній час (або правила невідомі) — фіксований зсув
            return "STD" + offset(rules.getStandardOffset(java.time.Instant.now()))
        }
        val start = tr.first { it.offsetAfter.totalSeconds > it.offsetBefore.totalSeconds }
        val end = tr.first { it !== start }
        return "STD" + offset(start.standardOffset) + "DST" + offset(start.offsetAfter) +
            "," + rule(start) + "," + rule(end)
    }

    /** POSIX пише зсув навпаки: UTC+2 → "-2". */
    private fun offset(o: ZoneOffset): String {
        val s = -o.totalSeconds
        val sign = if (s < 0) "-" else ""
        val a = kotlin.math.abs(s)
        val h = a / 3600
        val m = a % 3600 / 60
        return sign + h + if (m != 0) ":%02d".format(m) else ""
    }

    private fun rule(r: ZoneOffsetTransitionRule): String {
        val year = Year.now().value
        val monthLen = r.month.length(Year.isLeap(year.toLong()))
        val dom = r.dayOfMonthIndicator
        val dow = r.dayOfWeek
        val time = r.createTransition(year).dateTimeBefore.toLocalTime()
        val t = "/" + time.hour + (if (time.minute != 0) ":%02d".format(time.minute) else "")
        if (dow == null) {
            // фіксована дата: Jn (день року без 29 лютого)
            val d = r.createTransition(2023).dateTimeBefore.dayOfYear
            return "J$d$t"
        }
        val week = when {
            dom < 0 || dom > monthLen - 7 -> 5
            else -> (dom - 1) / 7 + 1
        }
        return "M${r.month.value}.$week.${dow.value % 7}$t"
    }
}
