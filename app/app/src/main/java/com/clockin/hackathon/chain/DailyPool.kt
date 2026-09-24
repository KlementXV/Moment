package com.clockin.hackathon.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta

/** Miroir des règles du pool journalier du programme (`economy.rs`,
 * `settlement.rs`). Toute divergence se voit comme un échec de transaction,
 * jamais comme un paiement faux : la chaîne recalcule tout. */
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

    /** Comptes restants d'une instruction qui règle ce profil jusqu'à `bound`. */
    fun poolMetas(
        programId: SolanaPublicKey,
        profile: ProfileAccount?,
        config: ConfigAccount?,
        now: Long,
        bound: Long,
    ): List<AccountMeta> {
        if (profile == null || config == null) return emptyList()
        val delay = config.poolCloseDelaySeconds
        val claims = profile.pendingDays.filter { it != NO_DAY && now >= closesAt(it, delay) }
            .map { AccountMeta(ClockInAddresses.dayPool(programId, it), false, false) }
        val penalty = if (profile.active && bound > profile.settledDay && now < closesAt(bound, delay))
            listOf(AccountMeta(ClockInAddresses.dayPool(programId, bound), false, true)) else emptyList()
        return claims + penalty
    }

    /** Jours dont l'app lit le pool : créances, borne de règlement et aujourd'hui. */
    fun poolDays(profile: ProfileAccount?, today: Long): Set<Long> = buildSet {
        add(today)
        profile?.pendingDays?.filter { it != NO_DAY }?.let(::addAll)
        profile?.let { add(settleBound(it, today)) }
    }
}
