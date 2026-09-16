package com.clockin.hackathon.chain

import com.solana.publickey.SolanaPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionBuilderTest {
    private val programId = SolanaPublicKey(Base58.decode("7TgCk9XekpU88Tiewd5VKfhmVJQyxNRR8915pzqU3rG1"))
    private val owner = SolanaPublicKey(ByteArray(32) { 5 })
    private val authority = SolanaPublicKey(ByteArray(32) { 6 })
    private val blockhash = "EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k"

    private fun checkInTransaction() = TransactionBuilder.build(
        listOf(
            ClockInInstructions.checkIn(
                programId, owner, authority, 20_706L, ByteArray(32) { 1 }, ByteArray(32) { 2 }
            )
        ),
        feePayer = owner,
        blockhash = blockhash,
    )

    @Test
    fun the_owner_pays_the_fees_and_comes_first() {
        assertEquals(owner, checkInTransaction().message.accounts.first())
    }

    @Test
    fun there_is_one_empty_signature_slot_per_signer() {
        val transaction = checkInTransaction()
        assertEquals(2, transaction.signatures.size)
        assertTrue(transaction.signatures.all { TransactionBuilder.isEmptySignature(it) })
    }

    @Test
    fun a_signature_lands_in_the_slot_of_its_own_signer() {
        val signature = ByteArray(64) { 9 }
        val transaction = TransactionBuilder.withSignature(checkInTransaction(), authority, signature)

        val index = transaction.message.accounts.indexOf(authority)
        assertArrayEquals(signature, transaction.signatures[index])
        val ownerIndex = transaction.message.accounts.indexOf(owner)
        assertTrue(TransactionBuilder.isEmptySignature(transaction.signatures[ownerIndex]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun signing_for_an_account_that_is_not_a_signer_is_refused() {
        TransactionBuilder.withSignature(checkInTransaction(), programId, ByteArray(64) { 9 })
    }

    @Test
    fun a_signature_survives_serialization_at_the_same_index() {
        val signature = ByteArray(64) { 9 }
        val transaction = TransactionBuilder.withSignature(checkInTransaction(), authority, signature)
        val index = TransactionBuilder.signatureIndex(transaction, authority)

        val serialized = transaction.serialize()
        assertArrayEquals(signature, TransactionBuilder.signatureAt(serialized, index))
    }

    @Test
    fun a_wiped_signature_can_be_restored_in_the_serialized_bytes() {
        val signature = ByteArray(64) { 9 }
        val transaction = TransactionBuilder.withSignature(checkInTransaction(), authority, signature)
        val index = TransactionBuilder.signatureIndex(transaction, authority)
        val serialized = transaction.serialize()

        // Un wallet qui écraserait notre signature au lieu de la préserver.
        val wiped = TransactionBuilder.withSignatureAt(serialized, index, ByteArray(64))
        assertTrue(TransactionBuilder.isEmptySignature(TransactionBuilder.signatureAt(wiped, index)))

        val repaired = TransactionBuilder.withSignatureAt(wiped, index, signature)
        assertArrayEquals(signature, TransactionBuilder.signatureAt(repaired, index))
        assertArrayEquals(serialized, repaired)
    }
}
