package com.ledgerlab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import java.util.Locale

/** Seconds of history across the trace. */
private const val WINDOW_SECONDS = 60.0

/** A close slower than this beats in gold. */
private const val SLOW_SECONDS = 5.0

/**
 * An ECG trace of ledger closes: every validated ledger is a beat, spaced by its real close interval,
 * scrolling left in real time. A regular rhythm means healthy consensus; gaps show slow rounds.
 */
@Composable
fun Heartbeat(
    intervals: List<Double>,
    lastCloseAtMs: Long,
    ledgerIndex: Long,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { nowMs = System.currentTimeMillis() }
    }
    val beat = remember { Animatable(1f) }
    LaunchedEffect(ledgerIndex) {
        if (ledgerIndex == 0L) return@LaunchedEffect
        beat.snapTo(0f)
        beat.animateTo(1f, tween(700))
    }

    val recent = intervals.takeLast(30)
    val avg = recent.average().takeIf { recent.isNotEmpty() } ?: 0.0
    val jitter = if (recent.size < 2) 0.0 else kotlin.math.sqrt(recent.sumOf { (it - avg) * (it - avg) } / recent.size)

    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (avg > 0) String.format(Locale.US, "%.1f", 60 / avg) else "—",
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = accent,
            )
            Text(
                "  ledgers / min",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (recent.size >= 2) String.format(Locale.US, "±%.2fs jitter", jitter) else "warming up",
                fontFamily = Mono,
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val mid = size.height * 0.62f
            for (i in 1..5) {
                val x = size.width * i / 6
                drawLine(accent.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            drawLine(accent.copy(alpha = 0.12f), Offset(0f, mid), Offset(size.width, mid), 1f)
            if (lastCloseAtMs == 0L) return@Canvas

            val pxPerSecond = (size.width / WINDOW_SECONDS).toFloat()
            val sinceLast = ((nowMs - lastCloseAtMs) / 1000.0).coerceAtLeast(0.0)
            // Beat times, newest first, as seconds before now.
            val beats = ArrayList<Pair<Double, Double>>()
            var age = sinceLast
            beats.add(age to (recent.lastOrNull() ?: 0.0))
            for (i in recent.indices.reversed()) {
                age += recent[i]
                if (age > WINDOW_SECONDS + 2) break
                beats.add(age to (recent.getOrNull(i - 1) ?: 0.0))
            }

            val amp = size.height * 0.55f
            val w = 6.dp.toPx()
            val path = Path().apply { moveTo(size.width, mid) }
            for ((beatAge, interval) in beats) {
                val x = size.width - (beatAge * pxPerSecond).toFloat()
                val tall = if (interval > SLOW_SECONDS) 0.7f else 1f
                path.lineTo(x + w * 1.6f, mid)
                path.lineTo(x + w, mid + amp * 0.12f)
                path.lineTo(x, mid - amp * tall)
                path.lineTo(x - w * 0.8f, mid + amp * 0.28f)
                path.lineTo(x - w * 1.6f, mid)
            }
            path.lineTo(-w * 2, mid)
            drawPath(
                path,
                Brush.horizontalGradient(listOf(accent.copy(alpha = 0.1f), accent)),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Slow closes get a gold marker above their spike.
            for ((beatAge, interval) in beats) {
                if (interval <= SLOW_SECONDS) continue
                val x = size.width - (beatAge * pxPerSecond).toFloat()
                drawCircle(LabColors.Gold, 3.dp.toPx(), Offset(x, mid - amp * 0.7f - 8.dp.toPx()))
            }
            // The newest beat glows as it lands.
            val newest = Offset(size.width - (sinceLast * pxPerSecond).toFloat(), mid - amp)
            val b = beat.value
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.8f * (1 - b) + 0.2f), Color.Transparent), newest, 16.dp.toPx()),
                16.dp.toPx(),
                newest,
            )
            drawCircle(Color.White, 2.5.dp.toPx(), newest)
        }
        Text(
            "Each spike is one ledger closing. Gold dots mark closes slower than ${SLOW_SECONDS.toInt()}s.",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextMuted,
        )
    }
}
