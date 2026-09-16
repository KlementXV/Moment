package com.clockin.hackathon.chain

import java.math.BigInteger

/** Base58 Bitcoin, l'encodage des adresses Solana. Sans dépendance Android :
 * toute la couche chain doit rester testable en JVM. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var value = BigInteger(1, bytes)
        val encoded = StringBuilder()
        while (value > BigInteger.ZERO) {
            val result = value.divideAndRemainder(BASE)
            encoded.append(ALPHABET[result[1].toInt()])
            value = result[0]
        }
        repeat(bytes.takeWhile { it == 0.toByte() }.size) { encoded.append(ALPHABET[0]) }
        return encoded.reverse().toString()
    }

    fun decode(value: String): ByteArray {
        var number = BigInteger.ZERO
        for (character in value) {
            val digit = ALPHABET.indexOf(character)
            require(digit >= 0) { "caractère Base58 invalide" }
            number = number * BASE + BigInteger.valueOf(digit.toLong())
        }
        val body = if (number == BigInteger.ZERO) {
            ByteArray(0)
        } else {
            val raw = number.toByteArray()
            if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        }
        val leadingZeros = value.takeWhile { it == ALPHABET[0] }.length
        return ByteArray(leadingZeros) + body
    }
}
