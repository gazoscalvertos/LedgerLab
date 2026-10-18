package com.ledgerlab.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.StreamTx

/** The biggest XRP payments seen since the dashboard connected. */
@Composable
fun WhaleFeed(whales: List<StreamTx>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (whales.isEmpty()) {
            Text("Watching for big XRP payments…", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
        whales.forEachIndexed { rank, tx ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${rank + 1}",
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        Format.xrp(tx.xrpDrops),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        color = if (tx.xrpDrops >= WHALE_DROPS) LabColors.Gold else LabColors.Matrix,
                    )
                    Text(
                        "${Format.shortAddress(tx.account)} → ${Format.shortAddress(tx.destination ?: "?")}",
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                }
                Text(
                    "L${tx.ledgerIndex}",
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
            }
        }
    }
}
