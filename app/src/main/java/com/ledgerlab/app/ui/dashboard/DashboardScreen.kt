package com.ledgerlab.app.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.components.CategoryDonut
import com.ledgerlab.app.ui.components.LabFeature
import com.ledgerlab.app.ui.components.LabsGrid
import com.ledgerlab.app.ui.components.LedgerPulse
import com.ledgerlab.app.ui.components.NetworkSwitcher
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.components.PeriodSwitcher
import com.ledgerlab.app.ui.components.Sparkline
import com.ledgerlab.app.ui.components.StatTile
import com.ledgerlab.app.ui.components.TxLane
import com.ledgerlab.app.ui.components.WhaleFeed
import com.ledgerlab.app.ui.components.networkColor
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.DashboardSnapshot
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.StatsPeriod
import com.ledgerlab.xrpl.StreamTx
import com.ledgerlab.xrpl.XrplNetwork
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@Composable
fun DashboardScreen(
    network: XrplNetwork,
    connection: ConnectionState,
    snapshot: DashboardSnapshot,
    transactions: Flow<StreamTx>,
    period: StatsPeriod,
    history: HistoryState,
    onSelectPeriod: (StatsPeriod) -> Unit,
    onRetryHistory: () -> Unit,
    onSelectNetwork: (XrplNetwork) -> Unit,
    onOpenFeature: (LabFeature) -> Unit,
) {
    val accent = networkColor(network)
    val insets = WindowInsets.systemBars.asPaddingValues()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LabColors.Background),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(network, connection, onSelectNetwork) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedgerPulse(
                    ledgerIndex = snapshot.ledgerIndex,
                    lastCloseAtMs = snapshot.lastCloseAtMs,
                    avgCloseSeconds = snapshot.avgCloseSeconds,
                    accent = accent,
                    modifier = Modifier.weight(1.1f).aspectRatio(1f),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("THROUGHPUT", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    Text(
                        String.format(Locale.US, "%.1f", snapshot.tps),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 44.sp,
                        color = accent,
                    )
                    Text("transactions / sec", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "peak ${String.format(Locale.US, "%.1f", snapshot.peakTps)} tx/s",
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                    Text(
                        "${Format.grouped(snapshot.txSeen)} txs watched",
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                }
            }
        }

        item(key = "lane-$network") {
            TxLane(transactions, Modifier.fillMaxWidth().height(260.dp))
        }

        item {
            Column {
                Text("STATS", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                PeriodSwitcher(period, onSelectPeriod, accent)
            }
        }

        if (period == StatsPeriod.LIVE) {
            item {
                StatGrid(snapshot, accent)
            }

            item {
                Panel(title = "Transactions per ledger", accent = accent, trailing = {
                    Text("last ${snapshot.tpsHistory.size} ledgers", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }) {
                    Sparkline(snapshot.tpsHistory, accent, Modifier.fillMaxWidth().height(110.dp))
                }
            }

            item {
                Panel(title = "What's happening on-ledger", accent = LabColors.Cyan, trailing = {
                    Text("last 20 ledgers", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }) {
                    CategoryDonut(snapshot.categoryCounts, Modifier.fillMaxWidth())
                }
            }

            item {
                Panel(title = "Biggest XRP payments", accent = LabColors.Gold, trailing = {
                    Text("this session", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }) {
                    WhaleFeed(snapshot.whales, Modifier.fillMaxWidth())
                }
            }
        } else {
            periodItems(period, history, accent, onRetryHistory)
        }

        item {
            LabSection(
                eyebrow = "THE BASICS",
                title = "Core XRPL features, hands on",
                features = LabFeature.entries.filter { !it.mockup },
                accent = accent,
                network = network,
                onOpenFeature = onOpenFeature,
            )
        }

        item {
            LabSection(
                eyebrow = "USE CASES",
                title = "Real-world apps built on the ledger",
                features = LabFeature.entries.filter { it.mockup },
                accent = accent,
                network = network,
                onOpenFeature = onOpenFeature,
            )
        }
    }
}

/** A titled group of lab cards that folds away when its header is tapped. Open by default. */
@Composable
private fun LabSection(
    eyebrow: String,
    title: String,
    features: List<LabFeature>,
    accent: androidx.compose.ui.graphics.Color,
    network: XrplNetwork,
    onOpenFeature: (LabFeature) -> Unit,
) {
    var open by rememberSaveable(eyebrow) { mutableStateOf(true) }
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$eyebrow · ${features.size}", style = MaterialTheme.typography.labelSmall, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = LabColors.TextSecondary,
                modifier = Modifier.rotate(turn),
            )
        }
        AnimatedVisibility(open) {
            Column {
                if (network.isRealMoney && features.any { !it.usableOn(network) }) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Anything that uses a wallet is off on Mainnet, since this is a demo app. Switch to Testnet to try it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.Gold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LabsGrid(onOpenFeature, Modifier.fillMaxWidth(), features = features, network = network)
            }
        }
    }
}

@Composable
private fun Header(network: XrplNetwork, connection: ConnectionState, onSelectNetwork: (XrplNetwork) -> Unit) {
    val accent = networkColor(network)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = LabColors.TextPrimary)) { append("Ledger") }
                    withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(LabColors.Matrix, LabColors.Cyan)))) { append("Lab") }
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f),
            )
            ConnectionBadge(connection, accent)
        }
        Text(
            "Live from the XRP Ledger ${network.label}",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        NetworkSwitcher(network, onSelectNetwork)
    }
}

@Composable
private fun ConnectionBadge(connection: ConnectionState, accent: androidx.compose.ui.graphics.Color) {
    val color = if (connection == ConnectionState.LIVE) accent else LabColors.Gold
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "live-dot",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(connection.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun StatGrid(s: DashboardSnapshot, accent: androidx.compose.ui.graphics.Color) {
    val tiles = listOf(
        Triple("Close time", if (s.avgCloseSeconds > 0) String.format(Locale.US, "%.2fs", s.avgCloseSeconds) else "—", "avg of last 10 ledgers"),
        Triple("Last ledger", "${s.lastLedgerTxns} txs", "in the latest close"),
        Triple("Base fee", if (s.feeBaseDrops > 0) "${s.feeBaseDrops} drops" else "—", Format.xrp(s.feeBaseDrops)),
        Triple("Reserve", if (s.reserveBaseDrops > 0) Format.xrp(s.reserveBaseDrops) else "—", "+${Format.xrp(s.reserveIncDrops)} per object"),
        Triple("XRP moved", Format.xrp(s.xrpMovedDrops), "payments, last 20 ledgers"),
        Triple("Success", String.format(Locale.US, "%.1f%%", s.successRate * 100), "tesSUCCESS rate"),
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
