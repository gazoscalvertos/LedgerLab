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
import com.ledgerlab.app.ui.labs.AmmLab
import com.ledgerlab.app.ui.labs.ChannelsLab
import com.ledgerlab.app.ui.labs.DexLab
import com.ledgerlab.app.ui.labs.EscrowLab
import com.ledgerlab.app.ui.labs.ExplorerLab
import com.ledgerlab.app.ui.labs.NftLab
import com.ledgerlab.app.ui.labs.TokensLab
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
                val back = { open = null }
                BackHandler(enabled = open != null, onBack = back)

                when (open) {
                    LabFeature.WALLET -> {
                        val vm: WalletViewModel = viewModel()
                        val state by vm.state.collectAsStateWithLifecycle()
                        WalletScreen(state, vm, onBack = back)
                    }
                    LabFeature.TOKENS -> TokensLab(onBack = back)
                    LabFeature.DEX -> DexLab(onBack = back)
                    LabFeature.NFTS -> NftLab(onBack = back)
                    LabFeature.ESCROW -> EscrowLab(onBack = back)
                    LabFeature.CHANNELS -> ChannelsLab(onBack = back)
                    LabFeature.AMM -> AmmLab(onBack = back)
                    LabFeature.EXPLORER -> ExplorerLab(onBack = back)
                    null -> {
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
