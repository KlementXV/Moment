package com.clockin.hackathon.i18n

import com.clockin.hackathon.SKR
import com.clockin.hackathon.ui.*
import com.clockin.hackathon.wallet.WalletFailure
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LocalizationTest {
    @After fun resetLanguage() { AppLanguage.select(null) }

    @Test fun every_translation_preserves_all_placeholders() {
        val placeholder = Regex("\\{\\d+\\}")
        Message.entries.forEach { message ->
            assertTrue(message.name, message.french.isNotBlank())
            assertTrue(message.name, message.english.isNotBlank())
            assertEquals(message.name,
                placeholder.findAll(message.french).map { it.value }.toSet(),
                placeholder.findAll(message.english).map { it.value }.toSet())
        }
    }

    @Test fun substitution_does_not_interpret_user_text_as_a_placeholder() {
        assertEquals("Use {1} café", Message.Use.format("en", "{1} café"))
        assertEquals("Utiliser {1} café", Message.Use.format("fr", "{1} café"))
    }

    @Test fun device_matching_uses_first_supported_language_then_english() {
        assertEquals("fr", AppLanguage.resolve(listOf("de", "fr", "en")))
        assertEquals("en", AppLanguage.resolve(listOf("en", "fr")))
        assertEquals("en", AppLanguage.resolve(listOf("es")))
        assertEquals("en", AppLanguage.resolve(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupported_selection_is_rejected() { AppLanguage.select("es") }

    @Test fun amounts_follow_language_without_changing_their_value() {
        AppLanguage.select("en")
        assertEquals("1.25", money(SKR + SKR / 4))
        assertEquals("0.000000001", formatSkrInput(1))
        assertEquals(1L, parseSkrAmount(formatSkrInput(1)))
        AppLanguage.select("fr")
        assertEquals("1,25", money(SKR + SKR / 4))
        assertEquals("0,000000001", formatSkrInput(1))
        assertEquals(1L, parseSkrAmount(formatSkrInput(1)))
    }

    @Test fun dates_follow_language_and_keep_the_same_utc_deadline() {
        val now = Instant.parse("2026-09-16T21:00:00Z").epochSecond
        val zone = ZoneId.of("Europe/Paris")
        AppLanguage.select("en")
        assertEquals("tomorrow at 02:00", nextMomentAt(now, zone))
        assertEquals("3 h 00 min left", publicationDeadline(now, zone))
        assertEquals("16 September at 23:00", localDateTime(now, zone))
        AppLanguage.select("fr")
        assertEquals("demain à 02:00", nextMomentAt(now, zone))
        assertEquals("16 septembre à 23:00", localDateTime(now, zone))
        assertEquals(10800, secondsUntilNextMoment(now).toInt())
    }

    @Test fun demo_copy_and_wallet_errors_switch_without_reinitializing_data() {
        AppLanguage.select("fr")
        val french = demoMoments(100)
        val error = WalletFailure.NotConnected.message
        AppLanguage.select("en")
        val english = demoMoments(100)
        assertEquals(french.map { it.name }, english.map { it.name })
        assertNotEquals(french.first().caption, english.first().caption)
        assertEquals("Connect your wallet to continue.", WalletFailure.NotConnected.message)
        assertEquals(WalletFailure.NotConnected.message, localizeError(error))
    }
}
