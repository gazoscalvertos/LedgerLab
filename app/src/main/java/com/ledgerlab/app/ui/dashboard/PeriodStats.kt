package com.ledgerlab.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ledgerlab.app.ui.components.CategoryDonut
import com.ledgerlab.app.ui.components.LabOutlineButton
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.components.Sparkline
import com.ledgerlab.app.ui.components.StatTile
import com.ledgerlab.app.ui.components.WhaleFeed
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.PeriodSnapshot
import com.ledgerlab.xrpl.StatsPeriod
import java.util.Locale

/** The dashboard's stats section for a past window, read back from ledger history. */
internal fun LazyListScope.periodItems(
    period: StatsPeriod,
    history: HistoryState,
    accent: Color,
    onRetry: () -> Unit,
) {
    when (history) {
        HistoryState.Idle -> Unit
        is HistoryState.Loading -> item(key = "history-loading") {
            Panel(Modifier.fillMaxWidth(), title = "Reading the last ${period.label}", accent = accent) {
                val fraction = if (history.total == 0) 0f else history.done.toFloat() / history.total
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent,
                    trackColor = LabColors.Outline,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (history.total == 0) "Finding where the period starts…" else "Sample ledger ${history.done} of ${history.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
            }
        }
        is HistoryState.Failed -> item(key = "history-failed") {
            Panel(Modifier.fillMaxWidth(), title = "Couldn't read the history", accent = LabColors.Orange) {
                Text(history.message, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                LabOutlineButton("Try again", onRetry, accent = LabColors.Orange)
            }
        }
        is HistoryState.Ready -> {
            val s = history.stats
            item(key = "history-grid") { PeriodGrid(s, accent) }
            item(key = "history-tps") {
                Panel(title = "Throughput across ${covered(s)}", accent = accent, trailing = {
                    Text("${s.sampled} sample ledgers", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }) {
                    Sparkline(s.tpsSeries, accent, Modifier.fillMaxWidth().height(110.dp))
                }
            }
            item(key = "history-mix") {
                Panel(title = "What happened on-ledger", accent = LabColors.Cyan, trailing = {
                    Text("sampled", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }) {
                    CategoryDonut(s.categoryCounts, Modifier.fillMaxWidth())
                }
            }
            item(key = "history-whales") {
                Panel(title = "Biggest XRP payments", accent = LabColors.Gold, trailing = {
                    Text("in the samples", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }) {
                    WhaleFeed(s.whales, Modifier.fillMaxWidth())
                }
            }
            item(key = "history-note") { MethodNote(s) }
        }
    }
}

@Composable
private fun PeriodGrid(s: PeriodSnapshot, accent: Color) {
    val tiles = listOf(
        Triple("Ledgers closed", Format.grouped(s.ledgers), String.format(Locale.US, "avg %.2fs each", s.avgCloseSeconds)),
        Triple("Transactions", "~" + Format.compact(s.estimatedTxs.toDouble()), "estimated"),
        Triple("Throughput", String.format(Locale.US, "%.1f tx/s", s.avgTps), String.format(Locale.US, "busiest sample %.1f", s.busiestTps)),
        Triple("XRP burned", Format.xrp(s.burnedDrops), "fees destroyed, exact"),
        Triple("XRP moved", "~" + Format.xrp(s.estimatedXrpMovedDrops), "payments, estimated"),
        Triple("Success", String.format(Locale.US, "%.1f%%", s.successRate * 100), "tesSUCCESS in samples"),
    )
    val colors = listOf(accent, LabColors.Cyan, LabColors.Violet, LabColors.Orange, LabColors.Gold, LabColors.Matrix)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.zip(colors).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (tile, color) ->
                    StatTile(tile.first, tile.second, tile.third, color, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MethodNote(s: PeriodSnapshot) {
    Column {
        if (s.truncated) {
            Text(
                "This node only keeps history back to ledger ${Format.grouped(s.from.index)}, so these cover the last ${covered(s)}.",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.Gold,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "Nodes don't keep running totals, so LedgerLab reads ledgers ${Format.grouped(s.from.index)} and " +
                "${Format.grouped(s.to.index)} for the exact close time and XRP burned, plus ${s.sampled} ledgers " +
                "spread between them. Counts, mix and volume are scaled up from those samples.",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextMuted,
        )
    }
}

/** The span actually covered, e.g. "24h" or "6h 40m" when the node's history is shorter. */
private fun covered(s: PeriodSnapshot): String {
    if (!s.truncated) return s.period.label
    val minutes = s.spanSeconds / 60
    val days = minutes / 1440
    val hours = (minutes % 1440) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
