package com.clockin.hackathon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'anneau du jour se lit sans le dessiner : ce qu'il affiche est une fonction
 * pure de l'horloge UTC, du check-in du jour et de la série.
 */
class DayRingStateTest {
    /** Un jeudi à minuit UTC pile : le jour 20_348 vient de commencer. */
    private val midnight = 20_348L * DAY_SECONDS

    @Test fun ring_is_full_at_utc_midnight() {
        assertEquals(1f, DayRingState.of(midnight, posted = false, streak = 3).remaining, 1e-6f)
    }

    @Test fun ring_is_half_empty_at_noon() {
        assertEquals(.5f, DayRingState.of(midnight + 12 * 3600, posted = false, streak = 3).remaining, 1e-6f)
    }

    @Test fun ring_runs_out_in_the_last_second_of_the_day() {
        val state = DayRingState.of(midnight + DAY_SECONDS - 1, posted = false, streak = 3)
        assertTrue("l'anneau doit être presque vide, pas plein", state.remaining < .0001f)
        assertTrue("mais jamais négatif", state.remaining >= 0f)
    }

    @Test fun a_published_moment_closes_the_ring() {
        val state = DayRingState.of(midnight + 12 * 3600, posted = true, streak = 3)
        assertEquals(DayPhase.Done, state.phase)
        assertEquals(1f, state.remaining, 1e-6f)
    }

    @Test fun the_last_three_hours_are_urgent() {
        assertEquals(
            DayPhase.Open,
            DayRingState.of(midnight + DAY_SECONDS - 3 * 3600 - 1, posted = false, streak = 3).phase,
        )
        assertEquals(
            DayPhase.Urgent,
            DayRingState.of(midnight + DAY_SECONDS - 3 * 3600, posted = false, streak = 3).phase,
        )
    }

    @Test fun a_published_moment_is_never_urgent() {
        assertEquals(
            DayPhase.Done,
            DayRingState.of(midnight + DAY_SECONDS - 60, posted = true, streak = 3).phase,
        )
    }

    @Test fun an_open_day_encourages_without_a_countdown() {
        val state = DayRingState.of(midnight + 14 * 3600 + 23 * 60 + 14, posted = false, streak = 3)
        assertEquals("À toi de jouer", state.caption)
    }

    @Test fun a_published_moment_celebrates_validation() {
        assertEquals("Moment validé · bravo !", DayRingState.of(midnight, posted = true, streak = 3).caption)
    }

    @Test fun an_empty_streak_invites_the_first_day() {
        val state = DayRingState.of(midnight, posted = false, streak = 0)
        assertEquals("Jour 1", state.center)
        assertEquals("ta série commence", state.label)
    }

    @Test fun a_single_day_stays_singular() {
        val state = DayRingState.of(midnight, posted = true, streak = 1)
        assertEquals("1", state.center)
        assertEquals("jour de série", state.label)
    }

    @Test fun several_days_take_the_plural() {
        assertEquals("jours de série", DayRingState.of(midnight, posted = true, streak = 7).label)
    }

    @Test fun the_description_spells_out_what_the_ring_draws() {
        val state = DayRingState.of(midnight + 14 * 3600 + 23 * 60 + 14, posted = false, streak = 7)
        assertEquals("7 jours de série. À toi de jouer.", state.description)
    }

    @Test fun the_description_of_a_published_day_says_so() {
        assertEquals(
            "7 jours de série. Moment validé · bravo !.",
            DayRingState.of(midnight, posted = true, streak = 7).description,
        )
    }

    /** L'horloge du téléphone peut précéder l'epoch d'un jour lors d'un test. */
    @Test fun a_negative_clock_stays_inside_the_ring() {
        val state = DayRingState.of(-1, posted = false, streak = 0)
        assertTrue(state.remaining in 0f..1f)
    }
}
