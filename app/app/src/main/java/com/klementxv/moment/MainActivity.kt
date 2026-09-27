package com.klementxv.moment

import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModelProvider
import com.klementxv.moment.ui.MomentApp
import com.klementxv.moment.wallet.WalletSession
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter

class MainActivity : ComponentActivity() {
    private val sender = ActivityResultSender(this)
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = "https://github.com/KlementXV/Moment".toUri(),
            iconUri = "/KlementXV/Moment/raw/refs/heads/master/app/app/src/main/ic_launcher-playstore.png".toUri(),
            identityName = "Moment",
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val model = ViewModelProvider(this)[MomentModel::class.java]
        model.wallet = WalletSession(adapter, sender,
            getSharedPreferences("wallet", MODE_PRIVATE))
        setContent { MomentApp(model) }
    }
}
