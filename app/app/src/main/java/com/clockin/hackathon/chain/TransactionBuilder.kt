package com.clockin.hackathon.chain

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import com.solana.transaction.toUnsignedTransaction

/** Assemblage des transactions. Le rang d'une signature dans la liste suit le
 * rang de son signataire dans les comptes du message : c'est la règle Solana,
 * et placer une signature au mauvais index produit une transaction invalide. */
object TransactionBuilder {
    const val SIGNATURE_SIZE = 64

    fun build(
        instructions: List<TransactionInstruction>,
        feePayer: SolanaPublicKey,
        blockhash: String,
    ): Transaction {
        val builder = Message.Builder()
        instructions.forEach { builder.addInstruction(it) }
        val message = builder.setRecentBlockhash(blockhash).build()
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

    /** Lit une signature dans une transaction **sérialisée**. Le format legacy
     * commence par un compte compact-u16 de signatures, puis les signatures. */
    fun signatureAt(serialized: ByteArray, index: Int): ByteArray {
        val count = serialized[0].toInt() and 0xFF
        require(count < 0x80) { "en-tête compact-u16 sur plusieurs octets non géré" }
        require(index < count) { "index de signature hors de la transaction" }
        val start = 1 + index * SIGNATURE_SIZE
        return serialized.copyOfRange(start, start + SIGNATURE_SIZE)
    }

    /** Réinsère une signature dans une transaction sérialisée. Sert à restaurer
     * la signature de l'autorité de publication si le wallet l'a effacée : la
     * spec MWA demande de préserver les signatures existantes, mais on vérifie
     * plutôt que de faire confiance. */
    fun withSignatureAt(serialized: ByteArray, index: Int, signature: ByteArray): ByteArray {
        require(signature.size == SIGNATURE_SIZE)
        val count = serialized[0].toInt() and 0xFF
        require(count < 0x80 && index < count)
        return serialized.copyOf().also {
            signature.copyInto(it, 1 + index * SIGNATURE_SIZE)
        }
    }
}
