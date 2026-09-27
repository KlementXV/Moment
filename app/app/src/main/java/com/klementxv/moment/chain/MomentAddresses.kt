package com.klementxv.moment.chain

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking

object MomentAddresses {
    fun config(programId: SolanaPublicKey): SolanaPublicKey =
        find(programId, listOf("config".toByteArray()))

    fun vault(programId: SolanaPublicKey): SolanaPublicKey =
        find(programId, listOf("vault".toByteArray()))

    fun profile(programId: SolanaPublicKey, owner: SolanaPublicKey): SolanaPublicKey =
        find(programId, listOf("profile".toByteArray(), owner.bytes))

    fun checkIn(programId: SolanaPublicKey, owner: SolanaPublicKey, day: Long): SolanaPublicKey =
        find(programId, listOf("checkin".toByteArray(), owner.bytes, BorshWriter().i64(day).build()))

    fun dayPool(programId: SolanaPublicKey, day: Long): SolanaPublicKey =
        find(programId, listOf("day_pool".toByteArray(), BorshWriter().i64(day).build()))

    fun associatedToken(owner: SolanaPublicKey, mint: SolanaPublicKey): SolanaPublicKey =
        find(
            MomentInstructions.ASSOCIATED_TOKEN_PROGRAM,
            listOf(owner.bytes, MomentInstructions.TOKEN_PROGRAM.bytes, mint.bytes),
        )

    private fun find(programId: SolanaPublicKey, seeds: List<ByteArray>): SolanaPublicKey =
        runBlocking { ProgramDerivedAddress.find(seeds, programId).getOrThrow() }
}
