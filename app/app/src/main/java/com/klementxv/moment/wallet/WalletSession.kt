package com.klementxv.moment.wallet

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr

import com.klementxv.moment.provenance.PostManifest
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.publickey.SolanaPublicKey
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

class WalletSession(
    private val adapter: MobileWalletAdapter,
    private val sender: ActivityResultSender,
    private val store: SharedPreferences,
) {
    var address: SolanaPublicKey? by mutableStateOf(null)
        private set

    init {
        adapter.authToken = store.getString(KEY_AUTH_TOKEN, null)
        address = store.getString(KEY_ADDRESS, null)?.let { runCatching { SolanaPublicKey.from(it) }.getOrNull() }
    }

    fun forget() {
        address = null
        adapter.authToken = null
        store.edit().remove(KEY_ADDRESS).remove(KEY_AUTH_TOKEN).apply()
    }

    private fun persist() {
        store.edit()
            .putString(KEY_ADDRESS, address?.base58())
            .putString(KEY_AUTH_TOKEN, adapter.authToken)
            .apply()
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
                    persist()
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
            is TransactionResult.Success -> { persist(); Result.success(result.payload) }
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

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_AUTH_TOKEN = "authToken"
    }
}
