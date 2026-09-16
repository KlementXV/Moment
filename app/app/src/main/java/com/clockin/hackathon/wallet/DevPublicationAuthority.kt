package com.clockin.hackathon.wallet

import android.os.Build
import androidx.annotation.RequiresApi
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
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
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
 * **Béquille de semaine 1.** Le keyserver du plan 03 remplace cette classe : la
 * capacité d'autoriser une publication ne doit pas vivre dans l'app.
 *
 * La clé est lue depuis `local.properties`, n'est jamais commitée, et ne vaut
 * que sur devnet. En build release, `DEV_AUTHORITY_SECRET` est vide : la clé
 * publique est nulle et la publication est refusée avec un message explicite.
 * C'est la vérité du moment — sans keyserver, une release ne peut pas publier.
 */
object DevPublicationAuthority {
    private val secretKey: ByteArray? = BuildConfig.DEV_AUTHORITY_SECRET
        .takeIf { it.isNotBlank() }
        ?.let { Base58.decode(it) }

    /** Signer ici exige Ed25519 dans `java.security`, soit Android 13. Le Seeker
     * en dispose ; un appareil plus ancien ne peut simplement pas publier tant
     * que le keyserver ne prend pas ce rôle. */
    private val supported: Boolean
        get() = secretKey != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val publicKey: SolanaPublicKey?
        get() = secretKey?.takeIf { supported }?.let { SolanaPublicKey(it.copyOfRange(32, 64)) }

    fun sign(message: ByteArray): ByteArray {
        val key = secretKey ?: error("Autorité de publication de développement non configurée")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            error("La co-signature locale exige Android 13. Le keyserver prendra ce rôle.")
        }
        return Ed25519.sign(message, key)
    }
}
