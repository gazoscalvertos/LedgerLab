package com.ledgerlab.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.FeePressure
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.PressureLevel
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

fun pressureColor(level: PressureLevel?): Color = when (level) {
    PressureLevel.CALM, null -> LabColors.Matrix
    PressureLevel.BUSY -> LabColors.Gold
    PressureLevel.CONGESTED -> LabColors.Danger
}

/**
 * A speedometer for the open ledger. The left half fills as the ledger fills up; past halfway the fee is
 * escalating and the needle swings into amber, then red.
 */
@Composable
fun FeeGauge(fee: FeePressure?, history: List<Double>, modifier: Modifier = Modifier) {
    val needle by animateFloatAsState((fee?.gauge ?: 0.0).toFloat(), tween(700), label = "fee-needle")
    val color by animateColorAsState(pressureColor(fee?.level), label = "fee-color")

    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.1f), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(2.1f)) {
                val stroke = 14.dp.toPx()
                val radius = minOf(size.width / 2, size.height) - stroke
                val center = Offset(size.width / 2, size.height - 4.dp.toPx())
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arc = Size(radius * 2, radius * 2)
                drawArc(LabColors.Outline, 180f, 180f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val zones = listOf(0f to 0.5f to LabColors.Matrix, 0.5f to 0.75f to LabColors.Gold, 0.75f to 1f to LabColors.Danger)
                for ((range, zone) in zones) {
                    val (from, to) = range
                    val lit = needle > from
                    drawArc(
                        zone.copy(alpha = if (lit) 0.9f else 0.25f),
                        180f + from * 180f + 1f,
                        (to - from) * 180f - 2f,
                        false,
                        topLeft,
                        arc,
                        style = Stroke(stroke),
                    )
                }
                // Ticks.
                for (i in 0..10) {
                    val a = Math.toRadians(180.0 + i * 18.0)
                    val inner = radius - stroke - 4.dp.toPx()
                    val outer = inner - (if (i % 5 == 0) 8.dp else 4.dp).toPx()
                    drawLine(
                        LabColors.TextMuted,
                        Offset(center.x + (inner * cos(a)).toFloat(), center.y + (inner * sin(a)).toFloat()),
                        Offset(center.x + (outer * cos(a)).toFloat(), center.y + (outer * sin(a)).toFloat()),
                        strokeWidth = 1.5f,
                    )
                }
                // Needle.
                val angle = Math.toRadians(180.0 + needle * 180.0)
                val length = radius - stroke * 1.5f
                val tip = Offset(center.x + (length * cos(angle)).toFloat(), center.y + (length * sin(angle)).toFloat())
                drawLine(color, center, tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.7f), Color.Transparent), tip, 12.dp.toPx()), 12.dp.toPx(), tip)
                drawCircle(color, 6.dp.toPx(), center)
                drawCircle(LabColors.Background, 2.5.dp.toPx(), center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 18.dp)) {
                Text(
                    fee?.level?.label?.uppercase() ?: "READING…",
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = color,
                )
                Text(
                    fee?.let { String.format(Locale.US, "×%.1f fee to get in", it.multiplier) } ?: "asking the network",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GaugeFact("Open ledger", fee?.let { "${it.currentLedgerSize}/${it.expectedLedgerSize}" } ?: "—", "txs vs expected", Modifier.weight(1f))
            GaugeFact("Queue", fee?.let { "${it.queueSize}" } ?: "—", fee?.let { "of ${Format.grouped(it.maxQueueSize.toLong())} slots" } ?: "", Modifier.weight(1f))
            GaugeFact("Fee now", fee?.let { Format.grouped(it.openLedgerFeeDrops) } ?: "—", "drops, base ${fee?.baseFeeDrops ?: "—"}", Modifier.weight(1f))
        }
        if (history.size > 1) {
            Spacer(Modifier.height(12.dp))
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val gap = 2.dp.toPx()
                val bar = (size.width - gap * (FEE_BARS - 1)) / FEE_BARS
                history.takeLast(FEE_BARS).forEachIndexed { i, g ->
                    val h = (g.toFloat().coerceIn(0.04f, 1f)) * size.height
                    val zone = when {
                        g >= 0.75 -> LabColors.Danger
                        g > 0.5 -> LabColors.Gold
                        else -> LabColors.Matrix
                    }
                    drawRect(zone.copy(alpha = 0.7f), Offset(i * (bar + gap), size.height - h), Size(bar, h))
                }
            }
            Text("last ${history.size} readings, every 4s", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
    }
}

private const val FEE_BARS = 30

@Composable
private fun GaugeFact(label: String, value: String, caption: String, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted, maxLines = 1)
    }
}
