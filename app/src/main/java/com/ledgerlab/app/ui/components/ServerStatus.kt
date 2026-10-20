package com.ledgerlab.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.NodeSnapshot
import java.util.Locale

/** The public server the dashboard streams from: its software, health and how well connected it is. */
@Composable
fun ServerStatus(n: NodeSnapshot?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (n == null) {
            Text("Asking the server about itself…", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            return@Column
        }
        Text(n.host, fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact("Version", n.buildVersion ?: "—", LabColors.Cyan, Modifier.weight(1f))
            Fact("State", n.serverState ?: "—", stateColor(n.serverState), Modifier.weight(1f))
            Fact("Peers", n.peers?.toString() ?: "—", LabColors.Violet, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact("Uptime", n.uptimeSeconds?.let(::uptime) ?: "—", LabColors.Matrix, Modifier.weight(1f))
            Fact(
                "Load",
                n.loadFactor?.let { String.format(Locale.US, "×%.1f", it) } ?: "—",
                if ((n.loadFactor ?: 1.0) > 1.0) LabColors.Gold else LabColors.Matrix,
                Modifier.weight(1f),
            )
            Fact("History", n.completeLedgers?.let(::historyLabel) ?: "—", LabColors.TextSecondary, Modifier.weight(1f))
        }
        Text(
            "Nobody publishes a count of every node on the network, so this is the server LedgerLab is connected to " +
                "and how many other servers it's linked with.",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextMuted,
        )
    }
}

private fun stateColor(state: String?): Color = when (state) {
    "full", "proposing", "validating" -> LabColors.Matrix
    null -> LabColors.TextSecondary
    else -> LabColors.Gold
}

private fun uptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${(seconds % 3_600) / 60}m"
        else -> "${seconds / 60}m"
    }
}

/** "32570-90000000" → "full"; otherwise how many ledgers back it keeps. */
private fun historyLabel(complete: String): String {
    val last = complete.split(',').lastOrNull()?.split('-') ?: return complete
    val start = last.firstOrNull()?.toLongOrNull() ?: return complete
    val end = last.getOrNull(1)?.toLongOrNull() ?: start
    if (start <= 32_570) return "full"
    return com.ledgerlab.xrpl.Format.compact((end - start).toDouble()) + " ledgers"
}

@Composable
private fun Fact(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, maxLines = 1)
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
