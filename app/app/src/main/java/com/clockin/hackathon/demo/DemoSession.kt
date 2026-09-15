package com.clockin.hackathon.demo

/** Disposable UX simulation. Never used to authorize transactions.
 * Amounts are hundredths of fictitious SKR, not production mint units.
 */
data class DemoSession(
    val available: Long = 0, val staked: Long = 0, val pool: Long = 100_000,
    val streak: Long = 0, val total: Long = 0, val lastCheckIn: Long = -1,
    val settledDay: Long = -1, val exitUnlock: Long = 0,
    val active: Boolean = false, val faucetClaimed: Boolean = false,
) {
    fun faucet(): DemoSession {
        check(!faucetClaimed)
        return copy(available = available + 10_000, faucetClaimed = true)
    }
    fun settle(now: Long): DemoSession {
        if (!active) return this
        val through = minOf(now / DAY - 1, if (exitUnlock > 0) exitUnlock / DAY - 1 else Long.MAX_VALUE)
        val missed = through - settledDay
        if (missed <= 0) return this
        var remaining = staked
        if (missed > 30) remaining = 0
        else repeat(missed.toInt()) { remaining = remaining * 7_500 / 10_000 }
        return copy(staked = remaining, pool = pool + staked - remaining, settledDay = through, streak = 0)
    }
    fun stake(amount: Long, now: Long): DemoSession {
        require(amount > 0 && amount <= available && exitUnlock == 0L)
        val state = settle(now)
        return state.copy(available = state.available - amount, staked = state.staked + amount,
            active = true, settledDay = if (active) state.settledDay else now / DAY - 1,
            streak = if (active) state.streak else 0)
    }
    fun checkIn(now: Long): DemoSession {
        val state = settle(now)
        check(state.active && state.staked >= MIN_STAKE && state.lastCheckIn != now / DAY)
        check(exitUnlock == 0L || now < exitUnlock)
        val reward = minOf(state.staked / 100, 100, state.pool)
        return state.copy(staked = state.staked + reward, pool = state.pool - reward,
            streak = state.streak + 1, total = state.total + 1,
            lastCheckIn = now / DAY, settledDay = now / DAY)
    }
    fun requestExit(now: Long): DemoSession {
        check(active && exitUnlock == 0L)
        return settle(now).copy(exitUnlock = now + 2 * DAY)
    }
    fun cancelExit(now: Long): DemoSession {
        check(exitUnlock > now)
        return copy(exitUnlock = 0)
    }
    fun withdraw(now: Long): DemoSession {
        check(exitUnlock > 0 && now >= exitUnlock)
        val state = settle(now)
        return state.copy(available = state.available + state.staked, staked = 0,
            active = false, streak = 0, exitUnlock = 0)
    }
    companion object {
        const val DAY = 86_400L
        const val MIN_STAKE = 1_000L
    }
}
