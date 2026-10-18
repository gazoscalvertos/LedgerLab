package com.ledgerlab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.ui.dashboard.DashboardScreen
import com.ledgerlab.app.ui.dashboard.DashboardViewModel
import com.ledgerlab.app.ui.theme.LedgerLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LedgerLabTheme {
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
                )
            }
        }
    }
}
