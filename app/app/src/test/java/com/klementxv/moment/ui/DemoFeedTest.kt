package com.klementxv.moment.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoFeedTest {
    private val thursday = 20_348L

    @Test fun the_same_day_always_shows_the_same_moments() {
        assertEquals(demoMoments(thursday), demoMoments(thursday))
    }

    @Test fun the_circle_renews_itself_the_next_day() {
        assertNotEquals(demoMoments(thursday), demoMoments(thursday + 1))
    }

    @Test fun the_circle_is_never_empty() {
        assertEquals(DEMO_MOMENTS, demoMoments(thursday).size)
        assertTrue("un cercle de démo doit rester lisible", DEMO_MOMENTS in 3..6)
    }

    @Test fun no_moment_is_left_blank() {
        demoMoments(thursday).forEach { moment ->
            assertTrue("nom vide dans $moment", moment.name.isNotBlank())
            assertTrue("sous-titre vide dans $moment", moment.subtitle.isNotBlank())
            assertTrue("légende vide dans $moment", moment.caption.isNotBlank())
        }
    }

    @Test fun two_people_never_share_the_same_day() {
        val names = demoMoments(thursday).map { it.name }
        assertEquals(names.toSet().size, names.size)
    }

    @Test fun the_illustrations_never_repeat_in_one_day() {
        val variants = demoMoments(thursday).map { it.variant }
        assertEquals(variants.toSet().size, variants.size)
    }

    @Test fun every_illustration_points_at_a_real_palette() {
        demoMoments(thursday).forEach { assertTrue("variant ${it.variant}", it.variant in 0 until DEMO_MOMENTS) }
    }

    @Test fun the_cast_changes_over_a_week_instead_of_alternating() {
        val casts = (0L..6L).map { demoMoments(thursday + it).map(DemoMoment::name).toSet() }
        assertEquals("une semaine ne doit pas rejouer deux distributions", 7, casts.toSet().size)
    }

    @Test fun a_negative_day_still_shows_a_circle() {
        assertEquals(DEMO_MOMENTS, demoMoments(-3).size)
        demoMoments(-3).forEach { assertTrue(it.name.isNotBlank()) }
    }
}
