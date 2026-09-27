package com.klementxv.moment.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.LegacyMessage
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import com.solana.transaction.toUnsignedTransaction

object TransactionBuilder {
    const val SIGNATURE_SIZE = 64
    val COMPUTE_BUDGET_PROGRAM = SolanaPublicKey.from("ComputeBudget111111111111111111111111111111")
    const val COMPUTE_UNIT_LIMIT = 200_000
    const val COMPUTE_UNIT_PRICE = 100_000L

    fun computeBudget(): List<TransactionInstruction> {
        val limit = java.nio.ByteBuffer.allocate(5).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(2).putInt(COMPUTE_UNIT_LIMIT).array()
        val price = java.nio.ByteBuffer.allocate(9).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(3).putLong(COMPUTE_UNIT_PRICE).array()
        return listOf(
            TransactionInstruction(COMPUTE_BUDGET_PROGRAM, emptyList(), limit),
            TransactionInstruction(COMPUTE_BUDGET_PROGRAM, emptyList(), price),
        )
    }

    fun build(
        instructions: List<TransactionInstruction>,
        feePayer: SolanaPublicKey,
        blockhash: String,
    ): Transaction {
        val instructions = if (instructions.any { it.programId == COMPUTE_BUDGET_PROGRAM }) instructions
            else computeBudget() + instructions
        val builder = Message.Builder()
        instructions.forEach { builder.addInstruction(it) }
        val built = builder.setRecentBlockhash(blockhash).build() as LegacyMessage
        val writable = instructions.flatMap { it.accounts }.filter { it.isWritable }.map { it.publicKey }.toSet()
        val unsigned = built.accounts.drop(built.signatureCount.toInt())
        val readonlyCount = unsigned.count { it !in writable }
        require(unsigned.takeLast(readonlyCount).all { it !in writable })
        val message = built.copy(readOnlyNonSigners = readonlyCount.toUByte())
        require(message.accounts.first() == feePayer) {
            "le payeur de frais doit être le premier compte signataire et modifiable"
        }
        return message.toUnsignedTransaction()
    }

    fun signatureIndex(transaction: Transaction, signer: SolanaPublicKey): Int {
        val index = transaction.message.accounts.indexOf(signer)
        require(index >= 0 && index < transaction.signatures.size) {
            "ce compte n'est pas signataire de cette transaction"
        }
        return index
    }

    fun withSignature(
        transaction: Transaction,
        signer: SolanaPublicKey,
        signature: ByteArray,
    ): Transaction {
        require(signature.size == SIGNATURE_SIZE) { "signature Ed25519 de 64 octets attendue" }
        val signatures = transaction.signatures.toMutableList()
        signatures[signatureIndex(transaction, signer)] = signature
        return Transaction(signatures, transaction.message)
    }

    fun isEmptySignature(signature: ByteArray): Boolean = signature.all { it == 0.toByte() }

    fun messageBytes(serialized: ByteArray): ByteArray {
        require(serialized.isNotEmpty())
        val count = serialized[0].toInt() and 0xff
        require(count in 1..16 && serialized.size > 1 + count * SIGNATURE_SIZE)
        return serialized.copyOfRange(1 + count * SIGNATURE_SIZE, serialized.size)
    }

    fun signatureAt(serialized: ByteArray, index: Int): ByteArray {
        val count = serialized[0].toInt() and 0xFF
        require(count < 0x80) { "en-tête compact-u16 sur plusieurs octets non géré" }
        require(index >= 0 && index < count && serialized.size >= 1 + count * SIGNATURE_SIZE) { "index de signature hors de la transaction" }
        val start = 1 + index * SIGNATURE_SIZE
        return serialized.copyOfRange(start, start + SIGNATURE_SIZE)
    }

    fun withSignatureAt(serialized: ByteArray, index: Int, signature: ByteArray): ByteArray {
        require(signature.size == SIGNATURE_SIZE)
        val count = serialized[0].toInt() and 0xFF
        require(count in 1..16 && index >= 0 && index < count && serialized.size >= 1 + count * SIGNATURE_SIZE)
        return serialized.copyOf().also {
            signature.copyInto(it, 1 + index * SIGNATURE_SIZE)
        }
    }

    data class Account(val key: List<Byte>, val signer: Boolean, val writable: Boolean)
    data class Instruction(val program: List<Byte>, val accounts: List<Account>, val data: List<Byte>)
    data class Decoded(val signerKeys: List<List<Byte>>, val blockhash: List<Byte>, val instructions: List<Instruction>)

    fun decode(message: ByteArray): Decoded {
        var r = 0
        fun u8(): Int { require(r < message.size); return message[r++].toInt() and 0xff }
        fun short(): Int { val v = u8(); require(v < 0x80) { "compact-u16 long non géré" }; return v }
        fun bytes(n: Int): List<Byte> { require(r + n <= message.size); return message.copyOfRange(r, r + n).toList().also { r += n } }
        require(message.isNotEmpty() && (message[0].toInt() and 0x80) == 0) { "message versionné non géré" }
        val signers = u8(); val readonlySigned = u8(); val readonlyUnsigned = u8()
        val count = short()
        require(signers in 1..count && readonlySigned < signers && readonlyUnsigned <= count - signers)
        val keys = List(count) { bytes(32) }
        require(keys.toSet().size == count)
        fun account(i: Int): Account {
            require(i < count)
            val writable = if (i < signers) i < signers - readonlySigned else i < count - readonlyUnsigned
            return Account(keys[i], i < signers, writable)
        }
        val blockhash = bytes(32)
        val instructions = List(short()) {
            val program = keys[u8().also { require(it < count) }]
            val accounts = List(short()) { account(u8()) }
            Instruction(program, accounts, bytes(short()))
        }
        require(r == message.size)
        return Decoded(keys.take(signers), blockhash, instructions)
    }

    fun sameMeaning(a: ByteArray, b: ByteArray): Boolean {
        val x = decode(a); val y = decode(b)
        return x.signerKeys == y.signerKeys && x.blockhash == y.blockhash && x.instructions == y.instructions
    }
}
