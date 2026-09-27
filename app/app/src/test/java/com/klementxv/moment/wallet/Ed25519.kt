package com.klementxv.moment.wallet

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec

object Ed25519 {
    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        require(secretKey.size == 64) { "clé secrète Ed25519 étendue de 64 octets attendue" }
        val seed = secretKey.copyOfRange(0, 32)
        val key = KeyFactory.getInstance("Ed25519")
            .generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed))
        return Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            sign()
        }
    }
}

