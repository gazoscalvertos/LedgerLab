package com.ledgerlab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Format

/**
 * A ring that fills while the network reaches consensus on the next ledger, then bursts when it
 * closes. The centre shows the latest validated ledger index.
 */
@Composable
fun LedgerPulse(
    ledgerIndex: Long,
    lastCloseAtMs: Long,
    avgCloseSeconds: Double,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { nowMs = System.currentTimeMillis() }
    }
    val burst = remember { Animatable(1f) }
    LaunchedEffect(ledgerIndex) {
        if (ledgerIndex == 0L) return@LaunchedEffect
        burst.snapTo(0f)
        burst.animateTo(1f, tween(900))
    }

    val interval = if (avgCloseSeconds > 0) avgCloseSeconds * 1000 else 3_800.0
    val progress = if (lastCloseAtMs == 0L) 0f else ((nowMs - lastCloseAtMs) / interval).toFloat().coerceIn(0f, 1f)
    val spin = (nowMs % 6_000L) / 6_000f * 360f

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 14.dp.toPx()
            val arcSize = Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            val radius = arcSize.width / 2

            // Burst ring on close.
            val b = burst.value
            if (b < 1f) {
                drawCircle(
                    color = accent.copy(alpha = (1f - b) * 0.6f),
                    radius = radius + b * 14.dp.toPx(),
                    center = center,
                    style = Stroke(width = (1f - b) * 8.dp.toPx() + 1f),
                )
            }

            // Ticks around the dial, slowly rotating.
            rotate(spin) {
                for (i in 0 until 60) {
                    val angle = Math.toRadians(i * 6.0)
                    val outer = radius + stroke + 6.dp.toPx()
                    val inner = outer - if (i % 5 == 0) 6.dp.toPx() else 3.dp.toPx()
                    drawLine(
                        color = accent.copy(alpha = if (i % 5 == 0) 0.5f else 0.2f),
                        start = center + Offset((kotlin.math.cos(angle) * inner).toFloat(), (kotlin.math.sin(angle) * inner).toFloat()),
                        end = center + Offset((kotlin.math.cos(angle) * outer).toFloat(), (kotlin.math.sin(angle) * outer).toFloat()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }

            drawArc(
                color = LabColors.Outline,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.1f), accent, LabColors.Cyan, accent.copy(alpha = 0.1f)), center),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            // Inner glow that flashes on close.
            drawCircle(
                brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.18f * (1.2f - b)), Color.Transparent), center, radius),
                radius = radius,
                center = center,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LEDGER", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Text(
                if (ledgerIndex == 0L) "…" else Format.grouped(ledgerIndex),
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                color = LabColors.TextPrimary,
            )
            Text(
                if (avgCloseSeconds > 0) "closes every %.1fs".format(avgCloseSeconds) else "syncing",
                style = MaterialTheme.typography.bodySmall,
                color = accent,
            )
        }
    }
}
