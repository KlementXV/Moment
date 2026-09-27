package com.klementxv.moment.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSessionTest {
    @Test
    fun each_failure_tells_the_user_what_to_do() {
        assertEquals(
            "Aucun wallet compatible trouvé. Installe un wallet Solana Mobile, puis réessaie.",
            WalletFailure.NoWallet.message,
        )
        assertTrue(WalletFailure.Cancelled.message.contains("réessayer"))
        assertTrue(WalletFailure.NotConnected.message.contains("Connecte"))
        assertTrue(WalletFailure.values().all { it.message.isNotBlank() })
    }

    @Test
    fun a_wallet_exception_carries_its_message() {
        val exception = WalletException(WalletFailure.NoAccount)
        assertEquals(WalletFailure.NoAccount, exception.failure)
        assertEquals(WalletFailure.NoAccount.message, exception.message)
    }
}
