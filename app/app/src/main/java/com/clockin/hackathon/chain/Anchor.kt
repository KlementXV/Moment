package com.clockin.hackathon.chain

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Conventions Anchor utilisées côté client. Les discriminants sont dérivés du
 * nom, comme le fait le programme ; les tests les figent sur les valeurs de
 * l'IDL, si bien qu'un renommage d'instruction casse les tests avant la prod. */
object Anchor {
    fun instructionDiscriminator(name: String): ByteArray = digest("global:$name")
    fun accountDiscriminator(name: String): ByteArray = digest("account:$name")

    private fun digest(preimage: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(preimage.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 8)
}

/** Écriture Borsh minimale : uniquement les types du programme clockin. */
class BorshWriter {
    private val buffer = ByteArrayOutputStream()

    fun bytes(value: ByteArray) = apply { buffer.write(value) }
    fun u8(value: Int) = apply { buffer.write(value and 0xFF) }
    fun bool(value: Boolean) = apply { u8(if (value) 1 else 0) }
    fun u16(value: Int) = apply { repeat(2) { u8(value shr (8 * it)) } }
    fun u32(value: Long) = apply { repeat(4) { u8((value ushr (8 * it)).toInt()) } }
    fun u64(value: Long) = apply { repeat(8) { u8((value ushr (8 * it)).toInt()) } }
    fun i64(value: Long) = u64(value)
    fun build(): ByteArray = buffer.toByteArray()
}

/** Lecture Borsh minimale, en miroir de `BorshWriter`. */
class BorshReader(private val data: ByteArray, private var offset: Int = 0) {
    fun bytes(count: Int): ByteArray =
        data.copyOfRange(offset, offset + count).also { offset += count }

    fun u8(): Int = data[offset++].toInt() and 0xFF
    fun bool(): Boolean = u8() == 1
    fun u16(): Int = u8() or (u8() shl 8)
    fun u32(): Long = (0 until 4).fold(0L) { acc, index -> acc or (u8().toLong() shl (8 * index)) }
    fun u64(): Long = (0 until 8).fold(0L) { acc, index -> acc or (u8().toLong() shl (8 * index)) }
    fun i64(): Long = u64()
    fun remaining(): Int = data.size - offset
}
