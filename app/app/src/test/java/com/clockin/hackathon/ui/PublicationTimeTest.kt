package com.clockin.hackathon.ui

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicationTimeTest {
    private fun time(value: String) = Instant.parse(value).epochSecond
    private val paris = ZoneId.of("Europe/Paris")

    @Test fun deadline_uses_the_local_date_on_either_side_of_local_midnight() {
        assertEquals("demain à 02:00", nextMomentAt(time("2026-09-16T21:00:00Z"), paris))
        assertEquals("aujourd’hui à 02:00", nextMomentAt(time("2026-09-16T22:30:00Z"), paris))
    }

    @Test fun american_deadline_is_on_the_same_local_day() {
        assertEquals("aujourd’hui à 17:00", nextMomentAt(time("2026-09-16T12:00:00Z"), ZoneId.of("America/Los_Angeles")))
    }

    @Test fun fractional_timezones_are_preserved() {
        assertEquals("demain à 05:45", nextMomentAt(time("2026-09-16T12:00:00Z"), ZoneId.of("Asia/Kathmandu")))
    }

    @Test fun next_deadline_uses_its_own_daylight_saving_offset() {
        assertEquals("demain à 02:00", nextMomentAt(time("2026-03-29T00:30:00Z"), paris))
        assertEquals("demain à 01:00", nextMomentAt(time("2026-10-25T00:30:00Z"), paris))
    }

    @Test fun countdown_is_readable_near_closing_and_never_displays_zero_minutes() {
        assertEquals("Il reste 3 h 00 min", publicationDeadline(time("2026-09-16T21:00:00Z"), paris))
        assertEquals("Il reste 18 min", publicationDeadline(time("2026-09-16T23:42:00Z"), paris))
        assertEquals("Il reste moins d’une minute", publicationDeadline(time("2026-09-16T23:59:59Z"), paris))
        assertEquals("Jusqu’à demain à 02:00", publicationDeadline(time("2026-09-16T12:00:00Z"), paris))
    }

    @Test fun utc_midnight_starts_a_new_window_even_if_local_date_has_not_changed() {
        assertEquals(1L, secondsUntilNextMoment(time("2026-09-16T23:59:59Z")))
        assertEquals(DAY_SECONDS, secondsUntilNextMoment(time("2026-09-17T00:00:00Z")))
        assertEquals("demain à 02:00", nextMomentAt(time("2026-09-17T00:00:00Z"), paris))
        assertEquals(1L, secondsUntilNextMoment(-1))
    }
}
