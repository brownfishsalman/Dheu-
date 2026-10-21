package com.salmansaadiq.dheu.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

private val shortOffset = Regex("[+-]\\d{2}$")
private val fullOffset = Regex("([+-]\\d{2}:\\d{2}|Z)$")

/**
 * Postgres timestamps arrive as "2026-10-21T09:56:47.318123+00:00" from the
 * REST API and "2026-10-21 09:56:47.318123+00" from Realtime; accept both.
 */
fun parseInstant(value: String): Instant {
    var s = value.trim().replace(' ', 'T')
    if (shortOffset.containsMatchIn(s)) s += ":00"
    return if (fullOffset.containsMatchIn(s)) OffsetDateTime.parse(s).toInstant()
    else LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
}

/** Instagram-style short ages: "now", "5m", "3h", "2d", "3w", "4mo", "1y" (same as the website). */
fun timeAgo(iso: String, now: Instant = Instant.now()): String {
    val s = Duration.between(parseInstant(iso), now).seconds.coerceAtLeast(0)
    if (s < 60) return "now"
    val m = s / 60
    if (m < 60) return "${m}m"
    val h = m / 60
    if (h < 24) return "${h}h"
    val d = h / 24
    if (d < 7) return "${d}d"
    val w = d / 7
    if (w < 5) return "${w}w"
    val mo = d / 30
    if (mo < 12) return "${mo}mo"
    return "${d / 365}y"
}

/** "Expires in 12 days" / "Expires in 3 hours" / "Expired". */
fun expiresIn(iso: String, now: Instant = Instant.now()): String {
    val ms = Duration.between(now, parseInstant(iso)).toMillis()
    if (ms <= 0) return "Expired"
    val h = ceilDiv(ms, 3_600_000L)
    if (h < 48) return "Expires in $h hour${if (h == 1L) "" else "s"}"
    val d = ceilDiv(ms, 86_400_000L)
    return "Expires in $d day${if (d == 1L) "" else "s"}"
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val dayYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

fun clock(iso: String): String = parseInstant(iso).atZone(ZoneId.systemDefault()).format(clockFormat)

fun localDate(iso: String): LocalDate = parseInstant(iso).atZone(ZoneId.systemDefault()).toLocalDate()

/** "Today", "Yesterday", "12 Oct", "12 Oct 2025". */
fun dayLabel(iso: String): String {
    val d = localDate(iso)
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(dayFormat)
        else -> d.format(dayYearFormat)
    }
}

fun minutesBetween(a: String, b: String): Long = Duration.between(parseInstant(a), parseInstant(b)).toMinutes()

fun daysLeft(iso: String): Long =
    ceilDiv(Duration.between(Instant.now(), parseInstant(iso)).toMillis(), 86_400_000L).coerceAtLeast(0)

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
