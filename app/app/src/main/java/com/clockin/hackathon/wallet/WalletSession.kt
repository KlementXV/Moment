package com.clockin.hackathon.wallet

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr

import com.clockin.hackathon.provenance.PostManifest
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.CancellationException

enum class WalletFailure {
    NoWallet, NoAccount, Cancelled, NotConnected, Unexpected;

    val message: String get() = tr(when (this) {
        NoWallet -> Message.WalletNoWallet
        NoAccount -> Message.WalletNoAccount
        Cancelled -> Message.WalletCancelled
        NotConnected -> Message.WalletNotConnected
        Unexpected -> Message.WalletUnexpected
    })
}

class WalletException(val failure: WalletFailure) : Exception(failure.message)

/**
 * Toutes les interactions wallet passent par ici. Aucune clé privée de
 * l'utilisateur ne transite par l'app : c'est le wallet qui signe, via MWA.
 *
 * Rien de ce qui passe ici n'est journalisé : ni manifeste, ni transaction,
 * ni signature.
 */
class WalletSession(
    private val adapter: MobileWalletAdapter,
    private val sender: ActivityResultSender,
) {
    var address: SolanaPublicKey? = null
        private set

    fun forget() {
        address = null
    }

    suspend fun connect(): Result<SolanaPublicKey> = try {
        when (val result = adapter.connect(sender)) {
            is TransactionResult.Success -> {
                val account = result.authResult.accounts.firstOrNull()
                if (account == null) {
                    Result.failure(WalletException(WalletFailure.NoAccount))
                } else {
                    val key = SolanaPublicKey(account.publicKey)
                    address = key
                    Result.success(key)
                }
            }
            is TransactionResult.NoWalletFound -> Result.failure(WalletException(WalletFailure.NoWallet))
            is TransactionResult.Failure -> Result.failure(WalletException(WalletFailure.Cancelled))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.failure(WalletException(WalletFailure.Unexpected))
    }

    /** Signature détachée du manifeste (§7) : le wallet signe des octets, pas
     * une transaction. C'est ce qui lie la paire d'images à son propriétaire. */
    suspend fun signManifest(manifest: PostManifest): Result<ByteArray> = signMessage(manifest.serialize())

    suspend fun signMessage(message: ByteArray): Result<ByteArray> {
        val wallet = address ?: return Result.failure(WalletException(WalletFailure.NotConnected))
        return wrap {
            adapter.transact(sender) { _ ->
                val result = signMessagesDetached(arrayOf(message), arrayOf(wallet.bytes))
                val signature = result.messages.first().signatures.first()
                require(signature.size == 64) { "signature de taille inattendue" }
                signature
            }
        }
    }

    /** Fait signer une transaction déjà sérialisée et éventuellement déjà
     * partiellement signée. Le résultat reste des octets : on ne re-parse pas
     * une transaction pour la renvoyer telle quelle au RPC. */
    suspend fun signTransaction(serialized: ByteArray): Result<ByteArray> {
        if (address == null) return Result.failure(WalletException(WalletFailure.NotConnected))
        return wrap {
            adapter.transact(sender) { _ ->
                signTransactions(arrayOf(serialized)).signedPayloads.first()
            }
        }
    }

    private suspend fun <T> wrap(block: suspend () -> TransactionResult<T>): Result<T> = try {
        when (val result = block()) {
            is TransactionResult.Success -> Result.success(result.payload)
            is TransactionResult.NoWalletFound ->
                Result.failure(WalletException(WalletFailure.NoWallet))
            is TransactionResult.Failure ->
                Result.failure(WalletException(WalletFailure.Cancelled))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: WalletException) {
        Result.failure(failure)
    } catch (_: Exception) {
        Result.failure(WalletException(WalletFailure.Unexpected))
    }
}
