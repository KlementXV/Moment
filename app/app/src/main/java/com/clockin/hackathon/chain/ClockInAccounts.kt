package com.clockin.hackathon.chain

/** Miroir Kotlin des comptes du programme. Les champs suivent l'ordre de
 * déclaration Rust : Borsh sérialise dans cet ordre, sans en-tête de champ. */
data class ConfigAccount(
    val admin: ByteArray,
    val publicationAuthority: ByteArray,
    val skrMint: ByteArray,
    val vault: ByteArray,
    val poolBalance: Long,
    val minStake: Long,
    val rewardCap: Long,
    val faucetAmount: Long,
    val withdrawalDelaySeconds: Long,
    val rewardRateBps: Int,
    val decayBps: Int,
    val maxDecayDays: Int,
    val faucetEnabled: Boolean,
)

data class ProfileAccount(
    val owner: ByteArray,
    val staked: Long,
    val settledDay: Long,
    val lastCheckInDay: Long,
    val exitRequestedAt: Long,
    val exitUnlockAt: Long,
    val totalCheckIns: Long,
    val streak: Long,
    val active: Boolean,
    val faucetClaimed: Boolean,
) {
    /** Solde tel qu'il sera après le règlement des jours déjà manqués.
     * L'affichage doit montrer ce que l'utilisateur a réellement, pas le solde
     * périmé stocké dans le compte ; le programme appliquera exactement le même
     * calcul à la prochaine instruction. */
    fun settledBalance(decayBps: Int, maxDecayDays: Int, today: Long): Long {
        if (!active) return 0
        val bound = if (exitUnlockAt > 0) {
            minOf(today - 1, Math.floorDiv(exitUnlockAt, 86_400L) - 1)
        } else {
            today - 1
        }
        val missed = bound - settledDay
        if (missed <= 0) return staked
        if (missed > maxDecayDays) return 0
        var remaining = staked.toBigInteger()
        val keep = (10_000 - decayBps).toBigInteger()
        val denominator = 10_000.toBigInteger()
        repeat(missed.toInt()) { remaining = remaining * keep / denominator }
        return remaining.toLong()
    }
}

data class CheckInAccount(
    val owner: ByteArray,
    val day: Long,
    val commitment: ByteArray,
    val blobRef: ByteArray,
    val slot: Long,
    val streakAtCheckIn: Long,
)

object ClockInAccounts {
    const val CHECK_IN_SIZE = 124
    const val CHECK_IN_DAY_OFFSET = 40

    fun decodeConfig(data: ByteArray): ConfigAccount {
        val reader = readerFor(data, "Config")
        return ConfigAccount(
            admin = reader.bytes(32),
            publicationAuthority = reader.bytes(32),
            skrMint = reader.bytes(32),
            vault = reader.bytes(32),
            poolBalance = reader.u64(),
            minStake = reader.u64(),
            rewardCap = reader.u64(),
            faucetAmount = reader.u64(),
            withdrawalDelaySeconds = reader.i64(),
            rewardRateBps = reader.u16(),
            decayBps = reader.u16(),
            maxDecayDays = reader.u8(),
            faucetEnabled = reader.bool(),
        )
    }

    fun decodeProfile(data: ByteArray): ProfileAccount {
        val reader = readerFor(data, "Profile")
        return ProfileAccount(
            owner = reader.bytes(32),
            staked = reader.u64(),
            settledDay = reader.i64(),
            lastCheckInDay = reader.i64(),
            exitRequestedAt = reader.i64(),
            exitUnlockAt = reader.i64(),
            totalCheckIns = reader.u64(),
            streak = reader.u32(),
            active = reader.bool(),
            faucetClaimed = reader.bool(),
        )
    }

    fun decodeCheckIn(data: ByteArray): CheckInAccount {
        val reader = readerFor(data, "CheckIn")
        return CheckInAccount(
            owner = reader.bytes(32),
            day = reader.i64(),
            commitment = reader.bytes(32),
            blobRef = reader.bytes(32),
            slot = reader.u64(),
            streakAtCheckIn = reader.u32(),
        )
    }

    private fun readerFor(data: ByteArray, name: String): BorshReader {
        require(data.size >= 8) { "compte tronqué" }
        require(data.copyOfRange(0, 8).contentEquals(Anchor.accountDiscriminator(name))) {
            "ce compte n'est pas un $name"
        }
        return BorshReader(data, 8)
    }
}
