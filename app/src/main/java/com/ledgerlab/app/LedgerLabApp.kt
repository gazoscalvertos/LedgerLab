package com.ledgerlab.app

import android.app.Application
import com.ledgerlab.app.data.NetworkStore
import com.ledgerlab.app.data.SettingsStore
import com.ledgerlab.app.data.WalletStore

/** Holds the app-wide stores every screen shares. */
class LedgerLabApp : Application() {
    val networkStore by lazy { NetworkStore(this) }
    val walletStore by lazy { WalletStore(this) }
    val settingsStore by lazy { SettingsStore(this) }
}
