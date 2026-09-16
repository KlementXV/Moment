package com.clockin.hackathon.wallet

import com.clockin.hackathon.BuildConfig
import com.clockin.hackathon.chain.Base58
import com.solana.publickey.SolanaPublicKey
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec

/**
 * Signature Ed25519 à partir d'une graine brute, via `java.security`.
 *
 * Disponible sur JDK 15+ et Android 13 (API 33+). C'est suffisant : ce code
 * n'existe que dans le build debug et disparaît avec l'arrivée du keyserver.
 * L'app de production ne signe jamais rien elle-même — c'est le wallet qui signe.
 */
object Ed25519 {
    /** `secretKey` est au format des fichiers solana-keygen : graine puis clé publique. */
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

/**
 * **Béquille de semaine 1, build debug uniquement.** Le keyserver du plan 03
 * remplace cette classe : la capacité d'autoriser une publication ne doit pas
 * vivre dans l'app. La clé est lue depuis `local.properties`, jamais commitée,
 * et ne vaut que sur devnet.
 */
object DevPublicationAuthority {
    private val secretKey: ByteArray? = BuildConfig.DEV_AUTHORITY_SECRET
        .takeIf { it.isNotBlank() }
        ?.let { Base58.decode(it) }

    val publicKey: SolanaPublicKey? = secretKey?.let { SolanaPublicKey(it.copyOfRange(32, 64)) }

    fun sign(message: ByteArray): ByteArray {
        val key = secretKey ?: error("Autorité de publication de développement non configurée")
        return Ed25519.sign(message, key)
    }
}
