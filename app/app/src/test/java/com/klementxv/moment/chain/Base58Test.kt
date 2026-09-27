package com.klementxv.moment.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Base58Test {
    @Test
    fun encodes_the_system_program_address() {
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
    }

    @Test
    fun round_trips_a_known_address() {
        val address = "7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1"
        val bytes = Base58.decode(address)
        assertEquals(32, bytes.size)
        assertEquals(address, Base58.encode(bytes))
    }

    @Test
    fun keeps_leading_zero_bytes() {
        val bytes = byteArrayOf(0, 0, 1, 2, 3)
        assertArrayEquals(bytes, Base58.decode(Base58.encode(bytes)))
        assertEquals("11", Base58.encode(bytes).take(2))
    }

    @Test
    fun encodes_short_and_high_bytes() {
        assertEquals("12", Base58.encode(byteArrayOf(0, 1)))
        assertEquals("5Q", Base58.encode(byteArrayOf(-1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_a_character_outside_the_alphabet() {
        Base58.decode("0OIl")
    }
}
