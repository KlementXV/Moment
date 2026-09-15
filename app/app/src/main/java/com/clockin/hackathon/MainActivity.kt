package com.clockin.hackathon

import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.clockin.hackathon.ui.MomentApp
import com.clockin.hackathon.capture.MomentModel
import androidx.lifecycle.ViewModelProvider
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigInteger

class MainActivity : ComponentActivity() {
    private val sender = ActivityResultSender(this)
    private val adapter = MobileWalletAdapter(connectionIdentity = ConnectionIdentity(
        identityUri = "https://clockin.hackathon".toUri(),
        iconUri = "favicon.ico".toUri(), identityName = "Moment"))
    private var walletAddress by mutableStateOf<String?>(null)
    private var connecting by mutableStateOf(false)
    private var walletError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val model = ViewModelProvider(this)[MomentModel::class.java]
        setContent { MomentApp(walletAddress, connecting, walletError, ::connectWallet, model) }
    }

    private fun connectWallet() {
        if (connecting) return
        connecting = true
        walletError = null
        lifecycleScope.launch {
            try {
                when (val result = adapter.connect(sender)) {
                    is TransactionResult.Success -> {
                        val account = result.authResult.accounts.firstOrNull()
                        if (account == null) walletError = "Le wallet n’a fourni aucun compte."
                        else walletAddress = encodeBase58(account.publicKey)
                    }
                    is TransactionResult.NoWalletFound -> walletError =
                        "Aucun wallet compatible trouvé. Installe un wallet Solana Mobile, puis réessaie."
                    is TransactionResult.Failure -> walletError =
                        "Connexion interrompue. Tu peux réessayer depuis ton wallet."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                walletError = "Connexion indisponible. Réessaie dans un instant."
            } finally { connecting = false }
        }
    }
}

internal fun encodeBase58(bytes: ByteArray): String {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var value = BigInteger(1, bytes)
    val base = BigInteger.valueOf(58)
    val encoded = StringBuilder()
    while (value > BigInteger.ZERO) {
        val result = value.divideAndRemainder(base)
        encoded.append(alphabet[result[1].toInt()])
        value = result[0]
    }
    repeat(bytes.takeWhile { it == 0.toByte() }.size) { encoded.append('1') }
    return encoded.reverse().toString()
}
