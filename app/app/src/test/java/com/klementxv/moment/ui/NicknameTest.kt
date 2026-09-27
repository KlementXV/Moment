package com.klementxv.moment.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NicknameTest {
    @Test fun nickname_is_optional() {
        assertTrue(validNickname(""))
        assertTrue(validNickname("   "))
    }
    @Test fun accepts_trimmed_names_and_rejects_invalid_or_overlong_values() {
        assertTrue(validNickname(" Clément.skr "))
        assertTrue(validNickname("a".repeat(24)))
        assertFalse(validNickname("a"))
        assertFalse(validNickname("a".repeat(25)))
        assertFalse(validNickname("name@wallet"))
    }
}
