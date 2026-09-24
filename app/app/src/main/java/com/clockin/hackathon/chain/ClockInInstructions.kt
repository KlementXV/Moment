package com.clockin.hackathon.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction

/** Construction des instructions. L'ordre des comptes suit exactement les
 * structures `#[derive(Accounts)]` du programme : toute divergence produit un
 * échec de contrainte côté chaîne, jamais un comportement silencieux. */
object ClockInInstructions {
    val SYSTEM_PROGRAM = SolanaPublicKey(ByteArray(32))
    val TOKEN_PROGRAM = SolanaPublicKey(Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"))
    val ASSOCIATED_TOKEN_PROGRAM =
        SolanaPublicKey(Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"))

    /** Création idempotente du compte de tokens associé : réussit même s'il
     * existe déjà, ce qui évite de sonder la chaîne avant chaque faucet. */
    fun createAssociatedTokenAccount(
        payer: SolanaPublicKey,
        owner: SolanaPublicKey,
        mint: SolanaPublicKey,
    ) = TransactionInstruction(
        ASSOCIATED_TOKEN_PROGRAM,
        listOf(
            AccountMeta(payer, true, true),
            AccountMeta(ClockInAddresses.associatedToken(owner, mint), false, true),
            AccountMeta(owner, false, false),
            AccountMeta(mint, false, false),
            AccountMeta(SYSTEM_PROGRAM, false, false),
            AccountMeta(TOKEN_PROGRAM, false, false),
        ),
        byteArrayOf(1),
    )

    fun createProfile(programId: SolanaPublicKey, owner: SolanaPublicKey) = TransactionInstruction(
        programId,
        listOf(
            AccountMeta(owner, true, true),
            AccountMeta(ClockInAddresses.profile(programId, owner), false, true),
            AccountMeta(SYSTEM_PROGRAM, false, false),
        ),
        Anchor.instructionDiscriminator("create_profile"),
    )

    fun faucet(
        programId: SolanaPublicKey,
        owner: SolanaPublicKey,
        mint: SolanaPublicKey,
        ownerTokenAccount: SolanaPublicKey,
    ) = TransactionInstruction(
        programId,
        listOf(
            AccountMeta(owner, true, true),
            AccountMeta(ClockInAddresses.config(programId), false, false),
            AccountMeta(ClockInAddresses.profile(programId, owner), false, true),
            AccountMeta(mint, false, true),
            AccountMeta(ownerTokenAccount, false, true),
            AccountMeta(TOKEN_PROGRAM, false, false),
        ),
        Anchor.instructionDiscriminator("faucet"),
    )

    fun stake(
        programId: SolanaPublicKey,
        owner: SolanaPublicKey,
        mint: SolanaPublicKey,
        ownerTokenAccount: SolanaPublicKey,
        amount: Long,
    ) = TransactionInstruction(
        programId,
        listOf(
            AccountMeta(owner, true, true),
            AccountMeta(ClockInAddresses.config(programId), false, true),
            AccountMeta(ClockInAddresses.profile(programId, owner), false, true),
            AccountMeta(mint, false, false),
            AccountMeta(ownerTokenAccount, false, true),
            AccountMeta(ClockInAddresses.vault(programId), false, true),
            AccountMeta(TOKEN_PROGRAM, false, false),
        ),
        BorshWriter().bytes(Anchor.instructionDiscriminator("stake")).u64(amount).build(),
    )

    fun checkIn(
        programId: SolanaPublicKey,
        owner: SolanaPublicKey,
        publicationAuthority: SolanaPublicKey,
        day: Long,
        commitment: ByteArray,
        blobRef: ByteArray,
        /** Pools de règlement (créances clôturées, dernier jour manqué) : `DailyPool.poolMetas`. */
        pools: List<AccountMeta> = emptyList(),
    ): TransactionInstruction {
        require(commitment.size == 32 && blobRef.size == 32)
        return TransactionInstruction(
            programId,
            listOf(
                AccountMeta(owner, true, true),
                AccountMeta(publicationAuthority, true, false),
                AccountMeta(ClockInAddresses.config(programId), false, false),
                AccountMeta(ClockInAddresses.profile(programId, owner), false, true),
                AccountMeta(ClockInAddresses.dayPool(programId, day), false, true),
                AccountMeta(ClockInAddresses.checkIn(programId, owner, day), false, true),
                AccountMeta(SYSTEM_PROGRAM, false, false),
            ) + pools,
            BorshWriter()
                .bytes(Anchor.instructionDiscriminator("check_in"))
                .i64(day)
                .bytes(commitment)
                .bytes(blobRef)
                .build(),
        )
    }

    fun requestExit(programId: SolanaPublicKey, owner: SolanaPublicKey) =
        exitRequest(programId, owner, "request_exit")

    fun cancelExit(programId: SolanaPublicKey, owner: SolanaPublicKey) =
        exitRequest(programId, owner, "cancel_exit")

    private fun exitRequest(programId: SolanaPublicKey, owner: SolanaPublicKey, name: String) =
        TransactionInstruction(
            programId,
            listOf(
                AccountMeta(owner, true, false),
                AccountMeta(ClockInAddresses.config(programId), false, true),
                AccountMeta(ClockInAddresses.profile(programId, owner), false, true),
            ),
            Anchor.instructionDiscriminator(name),
        )

    fun finalizeExit(
        programId: SolanaPublicKey,
        caller: SolanaPublicKey,
        owner: SolanaPublicKey,
        mint: SolanaPublicKey,
        ownerTokenAccount: SolanaPublicKey,
    ) = TransactionInstruction(
        programId,
        listOf(
            AccountMeta(caller, true, true),
            AccountMeta(owner, false, false),
            AccountMeta(ClockInAddresses.config(programId), false, true),
            AccountMeta(ClockInAddresses.profile(programId, owner), false, true),
            AccountMeta(mint, false, false),
            AccountMeta(ownerTokenAccount, false, true),
            AccountMeta(ClockInAddresses.vault(programId), false, true),
            AccountMeta(TOKEN_PROGRAM, false, false),
        ),
        Anchor.instructionDiscriminator("finalize_exit"),
    )
}
