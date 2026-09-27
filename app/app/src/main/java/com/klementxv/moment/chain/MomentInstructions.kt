package com.klementxv.moment.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction

object MomentInstructions {
    val SYSTEM_PROGRAM = SolanaPublicKey(ByteArray(32))
    val TOKEN_PROGRAM = SolanaPublicKey(Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"))
    val ASSOCIATED_TOKEN_PROGRAM =
        SolanaPublicKey(Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"))

    fun createAssociatedTokenAccount(
        payer: SolanaPublicKey,
        owner: SolanaPublicKey,
        mint: SolanaPublicKey,
    ) = TransactionInstruction(
        ASSOCIATED_TOKEN_PROGRAM,
        listOf(
            AccountMeta(payer, true, true),
            AccountMeta(MomentAddresses.associatedToken(owner, mint), false, true),
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
            AccountMeta(MomentAddresses.profile(programId, owner), false, true),
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
            AccountMeta(MomentAddresses.config(programId), false, false),
            AccountMeta(MomentAddresses.profile(programId, owner), false, true),
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
        day: Long,
        amount: Long,
        pools: List<AccountMeta> = emptyList(),
    ) = TransactionInstruction(
        programId,
        listOf(
            AccountMeta(owner, true, true),
            AccountMeta(MomentAddresses.config(programId), false, false),
            AccountMeta(MomentAddresses.profile(programId, owner), false, true),
            AccountMeta(MomentAddresses.dayPool(programId, day), false, true),
            AccountMeta(mint, false, false),
            AccountMeta(ownerTokenAccount, false, true),
            AccountMeta(MomentAddresses.vault(programId), false, true),
            AccountMeta(TOKEN_PROGRAM, false, false),
            AccountMeta(SYSTEM_PROGRAM, false, false),
        ) + pools,
        BorshWriter().bytes(Anchor.instructionDiscriminator("stake")).i64(day).u64(amount).build(),
    )

    fun checkIn(
        programId: SolanaPublicKey,
        owner: SolanaPublicKey,
        publicationAuthority: SolanaPublicKey,
        day: Long,
        commitment: ByteArray,
        blobRef: ByteArray,
        pools: List<AccountMeta> = emptyList(),
    ): TransactionInstruction {
        require(commitment.size == 32 && blobRef.size == 32)
        return TransactionInstruction(
            programId,
            listOf(
                AccountMeta(owner, true, true),
                AccountMeta(publicationAuthority, true, false),
                AccountMeta(MomentAddresses.config(programId), false, false),
                AccountMeta(MomentAddresses.profile(programId, owner), false, true),
                AccountMeta(MomentAddresses.dayPool(programId, day), false, true),
                AccountMeta(MomentAddresses.checkIn(programId, owner, day), false, true),
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

    fun requestExit(
        programId: SolanaPublicKey,
        owner: SolanaPublicKey,
        day: Long,
        pools: List<AccountMeta> = emptyList(),
    ) = TransactionInstruction(
        programId,
        listOf(
            AccountMeta(owner, true, true),
            AccountMeta(MomentAddresses.config(programId), false, false),
            AccountMeta(MomentAddresses.profile(programId, owner), false, true),
            AccountMeta(MomentAddresses.dayPool(programId, day), false, true),
            AccountMeta(SYSTEM_PROGRAM, false, false),
        ) + pools,
        BorshWriter().bytes(Anchor.instructionDiscriminator("request_exit")).i64(day).build(),
    )

    fun cancelExit(programId: SolanaPublicKey, owner: SolanaPublicKey) =
        TransactionInstruction(
            programId,
            listOf(
                AccountMeta(owner, true, false),
                AccountMeta(MomentAddresses.config(programId), false, false),
                AccountMeta(MomentAddresses.profile(programId, owner), false, true),
            ),
            Anchor.instructionDiscriminator("cancel_exit"),
        )

    fun finalizeExit(
        programId: SolanaPublicKey,
        caller: SolanaPublicKey,
        owner: SolanaPublicKey,
        mint: SolanaPublicKey,
        ownerTokenAccount: SolanaPublicKey,
        day: Long,
        pools: List<AccountMeta> = emptyList(),
    ) = TransactionInstruction(
        programId,
        listOf(
            AccountMeta(caller, true, true),
            AccountMeta(owner, false, false),
            AccountMeta(MomentAddresses.config(programId), false, false),
            AccountMeta(MomentAddresses.profile(programId, owner), false, true),
            AccountMeta(MomentAddresses.dayPool(programId, day), false, true),
            AccountMeta(mint, false, false),
            AccountMeta(ownerTokenAccount, false, true),
            AccountMeta(MomentAddresses.vault(programId), false, true),
            AccountMeta(TOKEN_PROGRAM, false, false),
            AccountMeta(SYSTEM_PROGRAM, false, false),
        ) + pools,
        BorshWriter().bytes(Anchor.instructionDiscriminator("finalize_exit")).i64(day).build(),
    )
}
