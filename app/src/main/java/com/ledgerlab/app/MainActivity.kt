package com.ledgerlab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.ui.components.LabFeature
import com.ledgerlab.app.ui.dashboard.DashboardScreen
import com.ledgerlab.app.ui.dashboard.DashboardViewModel
import com.ledgerlab.app.ui.theme.LedgerLabTheme
import com.ledgerlab.app.ui.wallet.WalletScreen
import com.ledgerlab.app.ui.wallet.WalletViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LedgerLabTheme {
                // A one-level stack is all the app needs: the dashboard, or one lab on top of it.
                var open by rememberSaveable { mutableStateOf<LabFeature?>(null) }
                BackHandler(enabled = open != null) { open = null }

                when (open) {
                    LabFeature.WALLET -> {
                        val vm: WalletViewModel = viewModel()
                        val state by vm.state.collectAsStateWithLifecycle()
                        WalletScreen(state, vm, onBack = { open = null })
                    }
                    else -> {
                        val vm: DashboardViewModel = viewModel()
                        val network by vm.network.collectAsStateWithLifecycle()
                        val connection by vm.connection.collectAsStateWithLifecycle()
                        val snapshot by vm.snapshot.collectAsStateWithLifecycle()
                        DashboardScreen(
                            network = network,
                            connection = connection,
                            snapshot = snapshot,
                            transactions = vm.transactions,
                            onSelectNetwork = vm::selectNetwork,
                            onOpenFeature = { if (it.available) open = it },
                        )
                    }
                }
            }
        }
    }
}
