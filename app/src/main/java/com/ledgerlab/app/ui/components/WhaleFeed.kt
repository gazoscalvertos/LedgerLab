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

/** The biggest XRP payments seen since the dashboard connected. Those at or above [whaleDrops] are gold. */
@Composable
fun WhaleFeed(
    whales: List<StreamTx>,
    whaleDrops: Long,
    modifier: Modifier = Modifier,
    ranked: Boolean = true,
    emptyText: String = "Watching for big XRP payments…",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (whales.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
        whales.forEachIndexed { rank, tx ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ranked) "#${rank + 1}" else "•",
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        Format.xrp(tx.xrpDrops),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        color = if (tx.xrpDrops >= whaleDrops) LabColors.Gold else LabColors.Matrix,
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
