package com.ledgerlab.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.ActivitySnapshot
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.SupplyState
import java.util.Locale

/** A count, with "~" when it was scaled up from samples. */
private fun count(n: Long, estimated: Boolean): String {
    val text = if (n < 10_000) Format.grouped(n) else Format.compact(n.toDouble())
    return if (estimated && n > 0) "~$text" else text
}

/** Offers placed and filled on the order books, the busiest pairs, and AMM pools gaining or losing liquidity. */
@Composable
fun DexAmmPulse(a: ActivitySnapshot, estimated: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact("Offers placed", count(a.offersCreated, estimated), LabColors.Cyan, Modifier.weight(1f))
            Fact("Offers filled", count(a.offersFilled, estimated), LabColors.Matrix, Modifier.weight(1f))
            Fact("Cancelled", count(a.offersCancelled, estimated), LabColors.TextSecondary, Modifier.weight(1f))
        }
        Column {
            Row {
                Text("FILLED VS PLACED", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.weight(1f))
                Text(
                    if (a.offersCreated == 0L) "—" else String.format(Locale.US, "%.0f%%", a.fillRate * 100),
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.Matrix,
                )
            }
            Spacer(Modifier.height(6.dp))
            Meter(a.fillRate.toFloat().coerceIn(0f, 1f), LabColors.Matrix)
        }
        SubHeading("Busiest pairs")
        if (a.topPairs.isEmpty()) Empty("No trades yet.")
        val pairMax = a.topPairs.maxOfOrNull { it.second } ?: 1L
        a.topPairs.forEach { (pair, n) -> RankBar(pair, null, count(n, estimated), n.toFloat() / pairMax, LabColors.Cyan) }

        SubHeading("AMM pools" + if (a.poolsCreated > 0) " · ${count(a.poolsCreated, estimated)} new" else "")
        if (a.pools.isEmpty()) Empty("No pool activity yet.")
        a.pools.forEach { pool ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pool.pair, fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
                    Text(
                        "${count(pool.deposits.toLong(), estimated)} in · ${count(pool.withdrawals.toLong(), estimated)} out · " +
                            "${count(pool.swaps.toLong(), estimated)} swaps",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextMuted,
                    )
                }
                val change = pool.liquidityChange
                Text(
                    when {
                        change <= -1.0 -> "drained"
                        kotlin.math.abs(change) < 0.0005 -> "±0%"
                        else -> String.format(Locale.US, "%+.1f%%", change * 100)
                    },
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        change > 0.0005 -> LabColors.Matrix
                        change < -0.0005 -> LabColors.Danger
                        else -> LabColors.TextSecondary
                    },
                )
            }
        }
        if (a.pools.isNotEmpty()) {
            Text(
                "The % is how much the pool's LP token supply moved, which tracks liquidity added or taken out.",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
            )
        }
    }
}

/** The issued tokens moving the most, and NFT mints, sales and burns. */
@Composable
fun TokenNftActivity(a: ActivitySnapshot, estimated: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SubHeading("Most active tokens")
        if (a.topTokens.isEmpty()) Empty("No issued tokens have moved yet.")
        val max = a.topTokens.maxOfOrNull { it.second } ?: 1L
        a.topTokens.forEach { (key, n) ->
            val currency = key.substringBefore('.')
            val issuer = key.substringAfter('.')
            RankBar(currency, Format.shortAddress(issuer), count(n, estimated), n.toFloat() / max, LabColors.Gold)
        }
        SubHeading("NFTs")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact("Minted", count(a.nftMints, estimated), LabColors.Magenta, Modifier.weight(1f))
            Fact("Sold", count(a.nftSales, estimated), LabColors.Matrix, Modifier.weight(1f))
            Fact("Offers", count(a.nftOffers, estimated), LabColors.Cyan, Modifier.weight(1f))
            Fact("Burned", count(a.nftBurns, estimated), LabColors.Orange, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact("Sales volume", (if (estimated && a.nftSaleVolumeDrops > 0) "~" else "") + Format.xrp(a.nftSaleVolumeDrops), LabColors.Gold, Modifier.weight(1f))
            Fact("Biggest sale", if (a.biggestNftSaleDrops > 0) Format.xrp(a.biggestNftSaleDrops) else "—", LabColors.Gold, Modifier.weight(1f))
        }
    }
}

/** New accounts against deleted ones, and the XRP destroyed by fees. */
@Composable
fun AccountGrowth(
    a: ActivitySnapshot,
    estimated: Boolean,
    supply: SupplyState?,
    /** XRP burned over the shown window: this session when live, or the exact figure for a past period. */
    burnedDrops: Long?,
    burnedLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact("New accounts", count(a.accountsCreated, estimated), LabColors.Matrix, Modifier.weight(1f))
            Fact("Deleted", count(a.accountsDeleted, estimated), LabColors.Danger, Modifier.weight(1f))
            val net = a.accountsCreated - a.accountsDeleted
            Fact("Net", (if (net > 0) "+" else "") + count(net, estimated), if (net >= 0) LabColors.Cyan else LabColors.Danger, Modifier.weight(1f))
        }
        Column {
            Text("XRP BURNED SINCE GENESIS", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                supply?.let { Format.grouped(it.burnedSinceGenesisDrops / 1_000_000) + " XRP" } ?: "—",
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = LabColors.Orange,
            )
            Text(
                supply?.let {
                    String.format(Locale.US, "%.5f%% of the 100B XRP ever created, gone for good", it.burnedSinceGenesisDrops * 100.0 / SupplyState.GENESIS_DROPS)
                } ?: "reading total_coins…",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(burnedLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(
                burnedDrops?.let { if (it < 1_000_000) Format.grouped(it) + " drops" else Format.xrp(it) } ?: "—",
                fontFamily = Mono,
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.Orange,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
}

@Composable
private fun Meter(fraction: Float, color: Color) {
    val shown by animateFloatAsState(fraction, label = "meter")
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(LabColors.Outline)) {
        Box(Modifier.fillMaxWidth(shown).height(8.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
private fun RankBar(label: String, sub: String?, value: String, fraction: Float, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary, maxLines = 1)
            if (sub != null) {
                Spacer(Modifier.width(8.dp))
                Text(sub, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(value, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Meter(fraction.coerceIn(0.02f, 1f), color.copy(alpha = 0.8f))
    }
}
