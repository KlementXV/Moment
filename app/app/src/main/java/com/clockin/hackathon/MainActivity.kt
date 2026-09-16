package com.clockin.hackathon

import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModelProvider
import com.clockin.hackathon.ui.MomentApp
import com.clockin.hackathon.wallet.WalletSession
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter

class MainActivity : ComponentActivity() {
    private val sender = ActivityResultSender(this)
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = "https://clockin.hackathon".toUri(),
            iconUri = "favicon.ico".toUri(),
            identityName = "Moment",
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val model = ViewModelProvider(this)[ClockInModel::class.java]
        // La session wallet a besoin de l'activité : elle est fournie ici plutôt
        // que construite dans le ViewModel.
        model.wallet = WalletSession(adapter, sender)
        setContent { MomentApp(model) }
    }
}
