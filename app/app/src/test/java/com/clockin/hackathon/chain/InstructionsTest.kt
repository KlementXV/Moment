package com.clockin.hackathon.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionsTest {
    private val programId = SolanaPublicKey(Base58.decode("7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1"))
    private val owner = SolanaPublicKey(Base58.decode("11111111111111111111111111111112"))
    private val mint = SolanaPublicKey(ByteArray(32) { 3 })
    private val authority = SolanaPublicKey(ByteArray(32) { 4 })

    @Test
    fun instruction_discriminators_match_the_generated_idl() {
        // Valeurs relevées dans program/target/idl/clockin.json.
        assertArrayEquals(
            byteArrayOf(-47, -3, 4, -39, -6, -15, -49, 50),
            Anchor.instructionDiscriminator("check_in")
        )
        assertArrayEquals(
            byteArrayOf(-50, -80, -54, 18, -56, -47, -77, 108),
            Anchor.instructionDiscriminator("stake")
        )
    }

    @Test
    fun account_discriminators_match_the_generated_idl() {
        assertArrayEquals(
            byteArrayOf(-101, 12, -86, -32, 30, -6, -52, -126),
            Anchor.accountDiscriminator("Config")
        )
        assertArrayEquals(
            byteArrayOf(-72, 101, -91, -68, 95, 63, 127, -68),
            Anchor.accountDiscriminator("Profile")
        )
        assertArrayEquals(
            byteArrayOf(16, 127, 31, -38, -34, -49, -76, 33),
            Anchor.accountDiscriminator("CheckIn")
        )
    }

    @Test
    fun stake_encodes_the_amount_in_little_endian() {
        val instruction = ClockInInstructions.stake(
            programId = programId, owner = owner, mint = mint,
            ownerTokenAccount = owner, day = 20_706L, amount = 1_000_000_000L
        )
        assertEquals(24, instruction.data.size)
        assertArrayEquals(Anchor.instructionDiscriminator("stake"), instruction.data.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(-30, 80, 0, 0, 0, 0, 0, 0), instruction.data.copyOfRange(8, 16))
        assertArrayEquals(
            byteArrayOf(0, -54, -102, 59, 0, 0, 0, 0),
            instruction.data.copyOfRange(16, 24)
        )
    }

    @Test
    fun check_in_encodes_day_commitment_and_blob_reference() {
        val commitment = ByteArray(32) { it.toByte() }
        val blobRef = ByteArray(32) { (100 + it).toByte() }
        val instruction = ClockInInstructions.checkIn(
            programId = programId, owner = owner, publicationAuthority = authority,
            day = 20_706L, commitment = commitment, blobRef = blobRef
        )
        assertEquals(80, instruction.data.size)
        assertArrayEquals(Anchor.instructionDiscriminator("check_in"), instruction.data.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(-30, 80, 0, 0, 0, 0, 0, 0), instruction.data.copyOfRange(8, 16))
        assertArrayEquals(commitment, instruction.data.copyOfRange(16, 48))
        assertArrayEquals(blobRef, instruction.data.copyOfRange(48, 80))
    }

    @Test
    fun check_in_carries_the_day_pool_and_the_settlement_pools() {
        val extra = AccountMeta(ClockInAddresses.dayPool(programId, 99), false, false)
        val instruction = ClockInInstructions.checkIn(
            programId = programId, owner = owner, publicationAuthority = authority,
            day = 100L, commitment = ByteArray(32), blobRef = ByteArray(32), pools = listOf(extra)
        )
        assertEquals(8, instruction.accounts.size)
        assertEquals(ClockInAddresses.config(programId), instruction.accounts[2].publicKey)
        assertFalse("la config n'est plus écrite", instruction.accounts[2].isWritable)
        assertEquals(ClockInAddresses.dayPool(programId, 100), instruction.accounts[4].publicKey)
        assertTrue(instruction.accounts[4].isWritable)
        assertEquals(ClockInAddresses.checkIn(programId, owner, 100), instruction.accounts[5].publicKey)
        assertEquals(extra, instruction.accounts[7])
    }

    @Test
    fun check_in_requires_both_signatures() {
        val instruction = ClockInInstructions.checkIn(
            programId = programId, owner = owner, publicationAuthority = authority,
            day = 1L, commitment = ByteArray(32), blobRef = ByteArray(32)
        )
        val signers = instruction.accounts.filter { it.isSigner }.map { it.publicKey }
        assertTrue(owner in signers)
        assertTrue(authority in signers)
    }

    @Test
    fun the_check_in_pda_depends_on_the_day() {
        val today = ClockInAddresses.checkIn(programId, owner, 20_706L)
        val tomorrow = ClockInAddresses.checkIn(programId, owner, 20_707L)
        assertTrue(!today.bytes.contentEquals(tomorrow.bytes))
    }

    @Test
    fun the_config_pda_is_stable() {
        assertEquals(
            ClockInAddresses.config(programId).base58(),
            ClockInAddresses.config(programId).base58()
        )
    }

    private val settlement = listOf(AccountMeta(ClockInAddresses.dayPool(programId, 99), false, true))

    @Test
    fun stake_carries_the_day_pool_then_the_settlement_pools() {
        val ix = ClockInInstructions.stake(programId, owner, mint, owner, 100L, 5L, settlement)
        assertEquals(
            listOf(owner, ClockInAddresses.config(programId), ClockInAddresses.profile(programId, owner),
                ClockInAddresses.dayPool(programId, 100), mint, owner, ClockInAddresses.vault(programId),
                ClockInInstructions.TOKEN_PROGRAM, ClockInInstructions.SYSTEM_PROGRAM) + settlement.map { it.publicKey },
            ix.accounts.map { it.publicKey },
        )
        assertFalse(ix.accounts[1].isWritable)
        assertTrue(ix.accounts[3].isWritable)
    }

    @Test
    fun request_exit_pays_for_the_day_pool_and_carries_the_day() {
        val ix = ClockInInstructions.requestExit(programId, owner, 100L, settlement)
        assertEquals(
            listOf(owner, ClockInAddresses.config(programId), ClockInAddresses.profile(programId, owner),
                ClockInAddresses.dayPool(programId, 100), ClockInInstructions.SYSTEM_PROGRAM) + settlement.map { it.publicKey },
            ix.accounts.map { it.publicKey },
        )
        assertTrue("le propriétaire paie le pool du jour", ix.accounts[0].isWritable)
        assertFalse(ix.accounts[1].isWritable)
        assertEquals(16, ix.data.size)
        assertArrayEquals(Anchor.instructionDiscriminator("request_exit"), ix.data.copyOfRange(0, 8))
    }

    @Test
    fun cancel_exit_keeps_its_three_accounts() {
        val ix = ClockInInstructions.cancelExit(programId, owner)
        assertEquals(3, ix.accounts.size)
        assertFalse(ix.accounts[1].isWritable)
        assertArrayEquals(Anchor.instructionDiscriminator("cancel_exit"), ix.data)
    }

    @Test
    fun finalize_exit_carries_the_day_pool_and_the_settlement_pools() {
        val caller = SolanaPublicKey(ByteArray(32) { 7 })
        val ix = ClockInInstructions.finalizeExit(programId, caller, owner, mint, owner, 100L, settlement)
        assertEquals(
            listOf(caller, owner, ClockInAddresses.config(programId), ClockInAddresses.profile(programId, owner),
                ClockInAddresses.dayPool(programId, 100), mint, owner, ClockInAddresses.vault(programId),
                ClockInInstructions.TOKEN_PROGRAM, ClockInInstructions.SYSTEM_PROGRAM) + settlement.map { it.publicKey },
            ix.accounts.map { it.publicKey },
        )
        assertEquals(16, ix.data.size)
    }

    @Test
    fun pool_metas_follow_the_program_rule() {
        val config = ConfigAccount(
            admin = ByteArray(32), publicationAuthority = ByteArray(32), skrMint = ByteArray(32),
            vault = ByteArray(32), minStake = 1, faucetAmount = 1, withdrawalDelaySeconds = 0,
            poolCloseDelaySeconds = 21_600, decayBps = 1_000, maxDecayDays = 30, faucetEnabled = true,
        )
        // Publié le 98, absent le 99.
        val profile = ProfileAccount(
            owner = ByteArray(32), staked = 100, settledDay = 98, lastCheckInDay = 98,
            exitRequestedAt = 0, exitUnlockAt = 0, totalCheckIns = 1, streak = 1,
            active = true, faucetClaimed = true, pendingDays = listOf(98L, -1L), pendingStakes = listOf(100L, 0L),
        )
        val morning = 100 * 86_400L + 3 * 3_600
        val metas = DailyPool.poolMetas(programId, profile, config, morning, bound = 99)
        assertEquals(listOf(ClockInAddresses.dayPool(programId, 98), ClockInAddresses.dayPool(programId, 99)), metas.map { it.publicKey })
        assertEquals(listOf(false, true), metas.map { it.isWritable })
        // Après 06:00, le pool du 99 est clôturé : on ne le passe plus.
        val later = DailyPool.poolMetas(programId, profile, config, morning + 4 * 3_600, bound = 99)
        assertEquals(listOf(ClockInAddresses.dayPool(programId, 98)), later.map { it.publicKey })
    }
}
