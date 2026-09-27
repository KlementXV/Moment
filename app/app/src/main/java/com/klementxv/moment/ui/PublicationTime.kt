package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr
import com.klementxv.moment.i18n.AppLanguage

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun secondsUntilNextMoment(now: Long): Long = DAY_SECONDS - Math.floorMod(now, DAY_SECONDS)

internal fun nextMomentAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val current = Instant.ofEpochSecond(now).atZone(zone)
    val next = Instant.ofEpochSecond(now + secondsUntilNextMoment(now)).atZone(zone)
    val day = when (next.toLocalDate()) {
        current.toLocalDate() -> tr(Message.Today)
        current.toLocalDate().plusDays(1) -> tr(Message.Tomorrow)
        else -> next.format(DateTimeFormatter.ofPattern("d MMMM", AppLanguage.locale))
    }
    return tr(Message.At, day, next.format(DateTimeFormatter.ofPattern("HH:mm", AppLanguage.locale)))
}

internal fun clockTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", AppLanguage.locale))

internal fun publicationDeadline(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val remaining = secondsUntilNextMoment(now)
    if (remaining > 3 * 3600) return tr(Message.Until, nextMomentAt(now, zone))
    if (remaining < 60) return tr(Message.LessThanAMinuteLeft)
    val hours = remaining / 3600
    val minutes = remaining % 3600 / 60
    return if (hours == 0L) tr(Message.MinLeft, minutes)
        else tr(Message.HMinLeft, hours, minutes.toString().padStart(2, '0'))
}

internal fun localDateTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(tr(Message.LocalDateTimePattern), AppLanguage.locale)
        .withZone(zone).format(Instant.ofEpochSecond(epochSeconds))
