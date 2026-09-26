package com.clockin.hackathon.ui

import com.clockin.hackathon.SKR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ce qu'on accepte quand on tape un montant à la main.
 *
 * Le champ borne la saisie, mais c'est ici que se décide ce qu'un texte vaut :
 * une virgule française comme un point, et neuf décimales — celles du mint.
 */
class AmountInputTest {
    @Test fun `a whole number is read in base units`() {
        assertEquals(500 * SKR, parseSkrAmount("500"))
    }

    @Test fun `the comma and the dot are the same separator`() {
        assertEquals(SKR / 2 + 12 * SKR, parseSkrAmount("12,5"))
        assertEquals(SKR / 2 + 12 * SKR, parseSkrAmount("12.5"))
    }

    @Test fun `the nine decimals of the mint all count`() {
        assertEquals(1L, parseSkrAmount("0,000000001"))
        assertEquals(SKR + 1, parseSkrAmount("1,000000001"))
    }

    @Test fun `decimals beyond the mint are dropped, not rounded`() {
        // Arrondir inventerait des unités que la chaîne ne peut pas représenter.
        assertEquals(1L, parseSkrAmount("0,0000000019"))
    }

    @Test fun `a missing whole part reads as zero`() {
        assertEquals(SKR / 2, parseSkrAmount(",5"))
    }

    @Test fun `an empty or malformed amount is not a number`() {
        assertNull(parseSkrAmount(""))
        assertNull(parseSkrAmount(","))
        assertNull(parseSkrAmount("1,2,3"))
        assertNull(parseSkrAmount("abc"))
        assertNull(parseSkrAmount("-5"))
    }

    @Test fun `an amount too large for the ledger is refused, not wrapped`() {
        assertNull(parseSkrAmount("99999999999999999999"))
    }

    @Test fun `what the field shows can be typed back, to the last unit`() {
        listOf(0L, 1L, SKR, 500 * SKR, 1_284 * SKR + SKR / 2, 3 * SKR + 123_456_789L).forEach { amount ->
            assertEquals(amount, parseSkrAmount(formatSkrInput(amount)))
        }
    }

    @Test fun `the field format drops the useless zeros but not the units`() {
        assertEquals("500", formatSkrInput(500 * SKR))
        assertEquals("12,5", formatSkrInput(12 * SKR + SKR / 2))
        assertEquals("0,000000001", formatSkrInput(1))
    }

    // ── Le vrai SKR (mainnet) a 6 décimales ─────────────────────────────────

    @Test fun `with six decimals a whole number is read in the real mint units`() {
        assertEquals(500_000_000L, parseSkrAmount("500", decimals = 6))
        assertEquals(12_500_000L, parseSkrAmount("12,5", decimals = 6))
        assertEquals(1L, parseSkrAmount("0,000001", decimals = 6))
    }

    @Test fun `with six decimals the seventh is dropped, not rounded`() {
        assertEquals(1L, parseSkrAmount("0,0000019", decimals = 6))
    }

    @Test fun `with six decimals the field round-trips to the last unit`() {
        listOf(0L, 1L, 1_000_000L, 500_000_000L, 1_284_500_000L, 3_123_456L).forEach { amount ->
            assertEquals(amount, parseSkrAmount(formatSkrInput(amount, decimals = 6), decimals = 6))
        }
        assertEquals("12,5", formatSkrInput(12_500_000L, decimals = 6))
        assertEquals("0,000001", formatSkrInput(1L, decimals = 6))
    }

    @Test fun `with six decimals the display reads whole SKR`() {
        assertEquals("500", skr(500_000_000L, decimals = 6))
        assertEquals("12,35", skr(12_345_678L, decimals = 6))
    }
}
