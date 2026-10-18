package com.ledgerlab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Filled area sparkline with a glowing last point. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        for (i in 1..3) {
            val y = size.height * i / 4
            drawLine(color.copy(alpha = 0.07f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.15
        val step = size.width / (values.size - 1)
        fun point(i: Int) = Offset(i * step, size.height - (values[i] / max * size.height).toFloat())

        val line = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (i in 1 until values.size) {
                val prev = point(i - 1)
                val cur = point(i)
                val midX = (prev.x + cur.x) / 2
                cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = point(values.lastIndex)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), last, 10.dp.toPx()), 10.dp.toPx(), last)
        drawCircle(Color.White, 2.5.dp.toPx(), last)
    }
}
