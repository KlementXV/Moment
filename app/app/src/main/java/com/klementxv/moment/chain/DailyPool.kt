package com.klementxv.moment.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta

object DailyPool {
    const val NO_DAY = -1L
    private const val DAY = 86_400L

    fun closesAt(day: Long, delay: Long): Long = (day + 1) * DAY + delay

    fun share(penalties: Long, stake: Long, totalStake: Long): Long {
        if (totalStake <= 0) return 0
        val raw = penalties.toBigInteger() * stake.toBigInteger() / totalStake.toBigInteger()
        return minOf(raw, penalties.toBigInteger()).toLong()
    }

    fun settleBound(profile: ProfileAccount, today: Long): Long =
        if (profile.exitUnlockAt > 0) minOf(today - 1, Math.floorDiv(profile.exitUnlockAt, DAY) - 1) else today - 1

    fun poolMetas(programId: SolanaPublicKey, profile: ProfileAccount?, bound: Long): List<AccountMeta> {
        if (profile == null) return emptyList()
        val claims = profile.pendingDays.filter { it != NO_DAY }
            .map { AccountMeta(MomentAddresses.dayPool(programId, it), false, false) }
        val penalty = if (profile.active && bound > profile.settledDay)
            listOf(AccountMeta(MomentAddresses.dayPool(programId, bound), false, true)) else emptyList()
        return claims + penalty
    }

    fun poolDays(profile: ProfileAccount?, today: Long): Set<Long> = buildSet {
        add(today)
        profile?.pendingDays?.filter { it != NO_DAY }?.let(::addAll)
        profile?.let { add(settleBound(it, today)) }
    }
}
