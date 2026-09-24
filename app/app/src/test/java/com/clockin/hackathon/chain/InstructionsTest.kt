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
            ownerTokenAccount = owner, amount = 1_000_000_000L
        )
        assertEquals(16, instruction.data.size)
        assertArrayEquals(Anchor.instructionDiscriminator("stake"), instruction.data.copyOfRange(0, 8))
        assertArrayEquals(
            byteArrayOf(0, -54, -102, 59, 0, 0, 0, 0),
            instruction.data.copyOfRange(8, 16)
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
}
