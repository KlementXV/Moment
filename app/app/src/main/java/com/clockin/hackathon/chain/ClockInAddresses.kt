package com.clockin.hackathon.chain

import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking

/** Dérivation des PDA du programme. `ProgramDerivedAddress.find` est une
 * fonction suspendue dans web3-solana ; on l'enveloppe ici pour que la couche
 * chain reste synchrone et testable. Le calcul est local, sans réseau. */
object ClockInAddresses {
    fun config(programId: SolanaPublicKey): SolanaPublicKey =
        find(programId, listOf("config".toByteArray()))

    fun vault(programId: SolanaPublicKey): SolanaPublicKey =
        find(programId, listOf("vault".toByteArray()))

    fun profile(programId: SolanaPublicKey, owner: SolanaPublicKey): SolanaPublicKey =
        find(programId, listOf("profile".toByteArray(), owner.bytes))

    fun checkIn(programId: SolanaPublicKey, owner: SolanaPublicKey, day: Long): SolanaPublicKey =
        find(programId, listOf("checkin".toByteArray(), owner.bytes, BorshWriter().i64(day).build()))

    private fun find(programId: SolanaPublicKey, seeds: List<ByteArray>): SolanaPublicKey =
        runBlocking { ProgramDerivedAddress.find(seeds, programId).getOrThrow() }
}
