package com.ledgerlab.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.TxCategory

/** Donut of what the network is being used for, with a legend. */
@Composable
fun CategoryDonut(counts: Map<TxCategory, Int>, modifier: Modifier = Modifier) {
    val total = counts.values.sum().coerceAtLeast(1)
    val sorted = counts.entries.sortedByDescending { it.value }
    val reveal by animateFloatAsState(if (counts.isEmpty()) 0f else 1f, label = "donut")

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(132.dp)) {
                val stroke = 18.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(LabColors.Outline, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                var start = -90f
                for ((category, count) in sorted) {
                    val sweep = 360f * count / total * reveal
                    drawArc(
                        color = LabColors.category(category),
                        startAngle = start,
                        sweepAngle = (sweep - 2f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(counts.values.sum().toString(), fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
                Text("txs", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sorted.isEmpty()) {
                Text("Waiting for transactions…", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            }
            sorted.take(6).forEach { (category, count) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(LabColors.category(category)))
                    Spacer(Modifier.width(8.dp))
                    Text(category.label, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text("${count * 100 / total}%", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                }
            }
        }
    }
}
