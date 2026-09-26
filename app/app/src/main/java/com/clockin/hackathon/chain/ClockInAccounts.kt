package com.clockin.hackathon.chain

/** Miroir Kotlin des comptes du programme. Les champs suivent l'ordre de
 * déclaration Rust : Borsh sérialise dans cet ordre, sans en-tête de champ. */
data class ConfigAccount(
    val admin: ByteArray,
    val publicationAuthority: ByteArray,
    val skrMint: ByteArray,
    val vault: ByteArray,
    val minStake: Long,
    val faucetAmount: Long,
    val withdrawalDelaySeconds: Long,
    /** Délai après la fin du jour D avant la clôture de son pool. */
    val poolCloseDelaySeconds: Long,
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
    /** Créances sur les pools des jours publiés, `DailyPool.NO_DAY` = emplacement libre. */
    val pendingDays: List<Long> = listOf(DailyPool.NO_DAY, DailyPool.NO_DAY),
    val pendingStakes: List<Long> = listOf(0, 0),
) {
    /** Solde tel qu'il sera après le règlement des jours déjà manqués.
     * L'affichage doit montrer ce que l'utilisateur a réellement, pas le solde
     * périmé stocké dans le compte ; le programme appliquera exactement le même
     * calcul à la prochaine instruction.
     *
     * `gain` : parts clôturées, que le programme encaisse avant de pénaliser. */
    fun settledBalance(decayBps: Int, maxDecayDays: Int, today: Long, gain: Long = 0): Long {
        if (!active) return 0
        val missed = DailyPool.settleBound(this, today) - settledDay
        if (missed <= 0) return staked + gain
        if (missed > maxDecayDays) return 0
        var remaining = (staked + gain).toBigInteger()
        val keep = (10_000 - decayBps).toBigInteger()
        val denominator = 10_000.toBigInteger()
        repeat(missed.toInt()) { remaining = remaining * keep / denominator }
        return remaining.toLong()
    }
}

data class DayPoolAccount(
    val day: Long,
    val penalties: Long,
    val totalStake: Long,
    val winnersCount: Long,
)

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
            minStake = reader.u64(),
            faucetAmount = reader.u64(),
            withdrawalDelaySeconds = reader.i64(),
            poolCloseDelaySeconds = reader.i64(),
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
        ).let { profile ->
            reader.u8() // bump
            profile.copy(
                pendingDays = listOf(reader.i64(), reader.i64()),
                pendingStakes = listOf(reader.u64(), reader.u64()),
            )
        }
    }

    /** Décimales d'un mint SPL Token : octet 44, après l'autorité de mint
     * (COption<Pubkey>, 36 octets) et l'offre (u64). L'octet 45 dit s'il est initialisé. */
    fun decodeMintDecimals(data: ByteArray): Int {
        require(data.size >= 82) { "mint tronqué" }
        require(data[45].toInt() == 1) { "mint non initialisé" }
        return data[44].toInt() and 0xFF
    }

    fun decodeDayPool(data: ByteArray): DayPoolAccount {
        val reader = readerFor(data, "DayPool")
        return DayPoolAccount(
            day = reader.i64(),
            penalties = reader.u64(),
            totalStake = reader.u64(),
            winnersCount = reader.u32(),
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
