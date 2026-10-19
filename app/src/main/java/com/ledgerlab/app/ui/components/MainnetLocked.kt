package com.ledgerlab.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.xrpl.XrplNetwork

/**
 * Shown in place of any screen that signs with a wallet while Mainnet is selected. LedgerLab is a demo, so it never
 * holds keys or sends transactions where the XRP is real.
 */
@Composable
fun MainnetLocked(
    feature: LabFeature,
    network: XrplNetwork,
    onSelectNetwork: (XrplNetwork) -> Unit,
    onBack: () -> Unit,
) {
    LabScaffold(
        title = feature.title,
        subtitle = feature.blurb,
        accent = feature.color,
        network = network,
        onSelectNetwork = onSelectNetwork,
        onBack = onBack,
    ) {
        item {
            Panel(Modifier.fillMaxWidth(), title = "Test networks only", accent = LabColors.Gold) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = LabColors.Gold, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "LedgerLab is a proof of concept, so anything that uses a wallet is switched off on Mainnet, " +
                        "where the XRP is real. The live dashboard and the explorer still work here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                LabButton("Switch to Testnet", { onSelectNetwork(XrplNetwork.TESTNET) }, Modifier.fillMaxWidth())
            }
        }
    }
}
