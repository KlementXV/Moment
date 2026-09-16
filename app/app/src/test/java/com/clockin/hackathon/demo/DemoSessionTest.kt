package com.clockin.hackathon.demo

import org.junit.Assert.*
import org.junit.Test

class DemoSessionTest {
    private val monday = 100 * DemoSession.DAY + 12 * 3600
    private fun position() = DemoSession().faucet().stake(5_000, monday)
    private fun total(s: DemoSession) = s.available + s.staked + s.pool

    @Test fun `faucet can only be claimed once`() {
        assertEquals(10_000, DemoSession().faucet().available)
        assertThrows(IllegalStateException::class.java) { DemoSession().faucet().faucet() }
    }
    @Test fun `check in rewards come from pool and duplicate is refused`() {
        val before = position()
        val after = before.checkIn(monday)
        assertEquals(5_050, after.staked)
        assertEquals(total(before), total(after))
        assertThrows(IllegalStateException::class.java) { after.checkIn(monday + 1) }
    }
    @Test fun `empty pool still permits check in`() {
        val after = position().copy(pool = 0).checkIn(monday)
        assertEquals(5_000, after.staked)
        assertEquals(1, after.streak)
    }
    @Test fun `only completed missed days decay and settling is idempotent`() {
        val before = position()
        assertEquals(before.staked, before.settle(monday + 60).staked)
        val after = before.settle(monday + 2 * DemoSession.DAY)
        assertEquals(2_812, after.staked)
        assertEquals(total(before), total(after))
        assertEquals(after, after.settle(monday + 2 * DemoSession.DAY))
    }
    @Test fun `new deposits do not suffer retroactive decay`() {
        val after = position().stake(1_000, monday + DemoSession.DAY)
        assertEquals(4_750, after.staked)
    }
    @Test fun `check in after settlement never applies decay twice`() {
        val settled = position().settle(monday + DemoSession.DAY)
        val after = settled.checkIn(monday + DemoSession.DAY)
        assertEquals(3_787, after.staked)
    }
    @Test fun `late exit freezes penalties at unlock day and conserves funds`() {
        val requested = position().checkIn(monday).requestExit(monday)
        assertThrows(IllegalStateException::class.java) { requested.withdraw(monday + DemoSession.DAY) }
        val onTime = requested.withdraw(monday + 2 * DemoSession.DAY)
        val late = requested.withdraw(monday + 9 * DemoSession.DAY)
        assertEquals(onTime, late)
        assertEquals(8_787, late.available)
        assertEquals(total(requested), total(late))
        assertFalse(late.active)
    }
    @Test fun `publishing during exit avoids decay`() {
        val requested = position().checkIn(monday).requestExit(monday)
        val nextDay = requested.checkIn(monday + DemoSession.DAY)
        val withdrawn = nextDay.withdraw(monday + 2 * DemoSession.DAY)
        assertEquals(10_100, withdrawn.available)
    }
    @Test fun `unlocked position cannot publish or cancel and pending exit cannot receive stake`() {
        val requested = position().requestExit(monday)
        assertThrows(IllegalStateException::class.java) { requested.checkIn(monday + 2 * DemoSession.DAY) }
        assertThrows(IllegalStateException::class.java) { requested.cancelExit(monday + 2 * DemoSession.DAY) }
        assertThrows(IllegalArgumentException::class.java) { requested.stake(1_000, monday) }
        assertEquals(0, requested.cancelExit(monday + 1).exitUnlock)
    }
    @Test fun `under minimum cannot publish and long absence clears stake`() {
        assertThrows(IllegalStateException::class.java) { position().copy(staked = 999).checkIn(monday) }
        assertEquals(0, position().settle(monday + 32 * DemoSession.DAY).staked)
    }
    @Test fun `reward is capped and UTC midnight opens next day`() {
        val state = position().copy(staked = 20_000)
        assertEquals(20_100, state.checkIn(monday).staked)
        val nextDay = (monday / DemoSession.DAY + 1) * DemoSession.DAY
        assertEquals(2, position().checkIn(nextDay - 1).checkIn(nextDay).streak)
    }
}
