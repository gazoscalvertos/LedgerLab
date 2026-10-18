package com.ledgerlab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.StreamTx
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow

private const val GLYPHS = "0123456789ABCDEFｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉ"
private const val HEX_GLYPHS = 16

/** Transactions land a ledger at a time; spread each batch over roughly one ledger interval. */
private const val DRAIN_SECONDS = 3.2
private const val MIN_RELEASE_PER_SECOND = 6.0
private const val MAX_PENDING = 800
private const val MAX_STREAKS = 320

/** XRP payments at or above this size are drawn as gold "whales". */
const val WHALE_DROPS = 50_000L * 1_000_000L

private class Streak(
    val lane: Int,
    val startNanos: Long,
    val durationNanos: Long,
    val color: Color,
    val trail: IntArray,
    val label: TextLayoutResult,
    val weight: Float,
)

private class RainColumn(var y: Float, val speed: Float, val length: Int, val seed: Int)

private class LaneState {
    val pending = ArrayDeque<StreamTx>()
    val streaks = ArrayList<Streak>()
    var rain: List<RainColumn> = emptyList()
    var rainWidth = 0f
    var lastRainNanos = 0L
    var lastFrameNanos = 0L
    var releaseBudget = 0.0
    var nextLane = 0
    var released = 0L
}

/**
 * A Matrix-style highway: every real transaction streaks across one of the lanes as soon as its
 * ledger validates, trailing the glyphs of its own hash, over a backdrop of falling code.
 */
@Composable
fun TxLane(
    transactions: Flow<StreamTx>,
    modifier: Modifier = Modifier,
    lanes: Int = 7,
) {
    val measurer = rememberTextMeasurer()
    val glyphStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    val glyphs = remember(measurer) { GLYPHS.map { measurer.measure(it.toString(), glyphStyle) } }
    val state = remember(transactions) { LaneState() }
    var frame by remember { mutableLongStateOf(0L) }
    var perSecond by remember { mutableIntStateOf(0) }

    LaunchedEffect(transactions) {
        transactions.collect { tx ->
            state.pending.addLast(tx)
            while (state.pending.size > MAX_PENDING) state.pending.removeFirst()
        }
    }

    LaunchedEffect(state) {
        var windowStart = 0L
        var windowCount = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (state.lastFrameNanos == 0L) 0.0 else (now - state.lastFrameNanos) / 1e9
                state.lastFrameNanos = now
                releaseStreaks(state, now, dt, lanes, measurer)
                state.streaks.removeAll { now - it.startNanos > it.durationNanos }
                if (now - windowStart > 1_000_000_000L) {
                    perSecond = (state.released - windowCount).toInt()
                    windowCount = state.released
                    windowStart = now
                }
                frame = now
            }
        }
    }

    Box(modifier.clip(PanelShape).background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val now = frame
            drawBackdrop(state, glyphs, now, lanes)
            drawStreaks(state, glyphs, now, lanes)
        }
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(LabColors.Matrix))
                Spacer(Modifier.width(6.dp))
                Text("TX LANE", style = MaterialTheme.typography.labelSmall, color = LabColors.Matrix)
            }
        }
        Text(
            "$perSecond tx/s",
            style = MaterialTheme.typography.labelSmall,
            color = LabColors.Matrix.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

private fun releaseStreaks(state: LaneState, now: Long, dt: Double, lanes: Int, measurer: TextMeasurer) {
    val rate = max(state.pending.size / DRAIN_SECONDS, MIN_RELEASE_PER_SECOND)
    state.releaseBudget = minOf(state.releaseBudget + rate * dt, ceil(rate))
    while (state.releaseBudget >= 1.0 && state.pending.isNotEmpty()) {
        state.releaseBudget -= 1.0
        val tx = state.pending.removeFirst()
        state.streaks.add(newStreak(tx, now, state.nextLane, measurer))
        state.nextLane = (state.nextLane + 1 + Random.nextInt(lanes - 1)) % lanes
        state.released++
    }
    if (state.pending.isEmpty()) state.releaseBudget = minOf(state.releaseBudget, 1.0)
    while (state.streaks.size > MAX_STREAKS) state.streaks.removeAt(0)
}

private fun newStreak(tx: StreamTx, now: Long, lane: Int, measurer: TextMeasurer): Streak {
    val whale = tx.xrpDrops >= WHALE_DROPS
    val color = when {
        !tx.succeeded -> LabColors.Danger
        whale -> LabColors.Gold
        else -> LabColors.category(tx.category)
    }
    val amount = Format.amount(tx.amount)
    val text = buildString {
        if (whale) append("WHALE ")
        append(tx.type)
        if (amount.isNotEmpty() && (tx.amount is Amount.Xrp || tx.amount is Amount.Issued)) append("  ").append(amount)
    }
    val label = measurer.measure(
        text,
        TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (whale) 12.sp else 10.sp),
    )
    val trailLength = if (whale) 26 else 10 + Random.nextInt(8)
    val hash = tx.hash.ifEmpty { "0" }
    val trail = IntArray(trailLength) { i -> hash[i % hash.length].digitToIntOrNull(16) ?: Random.nextInt(HEX_GLYPHS) }
    // Real XRPL speed: most streaks cross the screen in well under a second.
    val duration = if (whale) 1_600_000_000L else 520_000_000L + Random.nextLong(420_000_000L)
    return Streak(lane, now, duration, color, trail, label, if (whale) 1.8f else 1f)
}

private fun DrawScope.laneCenter(lane: Int, lanes: Int): Float {
    val top = 34.dp.toPx()
    val usable = size.height - top - 10.dp.toPx()
    return top + usable * (lane + 0.5f) / lanes
}

private fun DrawScope.drawBackdrop(state: LaneState, glyphs: List<TextLayoutResult>, now: Long, lanes: Int) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF020805), Color(0xFF000000), Color(0xFF02060A))))

    // Falling code rain.
    val cell = 14.dp.toPx()
    if (state.rainWidth != size.width) {
        state.rainWidth = size.width
        val columns = (size.width / cell).toInt() + 1
        state.rain = List(columns) {
            RainColumn(
                y = Random.nextFloat() * size.height,
                speed = (40 + Random.nextInt(120)).dp.toPx(),
                length = 6 + Random.nextInt(14),
                seed = Random.nextInt(),
            )
        }
    }
    val dt = if (state.lastRainNanos == 0L) 0f else ((now - state.lastRainNanos) / 1e9f).coerceIn(0f, 0.1f)
    state.lastRainNanos = now
    val tick = (now / 90_000_000L).toInt()
    state.rain.forEachIndexed { column, rain ->
        rain.y += rain.speed * dt
        if (rain.y - rain.length * cell > size.height) rain.y = -Random.nextFloat() * size.height * 0.5f
        for (i in 0 until rain.length) {
            val y = rain.y - i * cell
            if (y < -cell || y > size.height) continue
            val glyph = glyphs[Math.floorMod(rain.seed + i * 31 + (if (i == 0) tick else tick / 4), glyphs.size)]
            val alpha = if (i == 0) 0.35f else 0.18f * (1f - i.toFloat() / rain.length)
            drawText(glyph, color = LabColors.Matrix, topLeft = Offset(column * cell, y), alpha = alpha)
        }
    }

    // Lane markings that scroll to sell the speed.
    val phase = (now / 4_000_000L % 1000L).toFloat()
    val dash = PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 14.dp.toPx()), -phase)
    for (lane in 0..lanes) {
        val y = if (lane == lanes) laneCenter(lane - 1, lanes) + (laneCenter(1, lanes) - laneCenter(0, lanes)) / 2
        else laneCenter(lane, lanes) - (laneCenter(1, lanes) - laneCenter(0, lanes)) / 2
        drawLine(
            color = LabColors.Matrix.copy(alpha = if (lane == 0 || lane == lanes) 0.22f else 0.08f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = if (lane == 0 || lane == lanes) null else dash,
        )
    }
}

private fun DrawScope.drawStreaks(state: LaneState, glyphs: List<TextLayoutResult>, now: Long, lanes: Int) {
    val spacing = 9.dp.toPx()
    for (streak in state.streaks) {
        val progress = ((now - streak.startNanos).toFloat() / streak.durationNanos).coerceIn(0f, 1f)
        val trailWidth = streak.trail.size * spacing
        val travel = size.width + trailWidth + streak.label.size.width
        val headX = -streak.label.size.width + progress * travel
        val y = laneCenter(streak.lane, lanes)

        // Motion blur bar.
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, streak.color.copy(alpha = 0.55f)),
                startX = headX - trailWidth,
                endX = headX,
            ),
            start = Offset(headX - trailWidth, y),
            end = Offset(headX, y),
            strokeWidth = 2.dp.toPx() * streak.weight,
            cap = StrokeCap.Round,
        )

        // Hash glyph trail.
        val glyphHalf = glyphs[0].size.height / 2f
        for (i in streak.trail.indices) {
            val x = headX - (i + 1) * spacing
            if (x < -spacing || x > size.width) continue
            val alpha = (1f - i.toFloat() / streak.trail.size) * 0.9f
            drawText(glyphs[streak.trail[i]], color = streak.color, topLeft = Offset(x, y - glyphHalf), alpha = alpha)
        }

        // Glowing head.
        val glow = 10.dp.toPx() * streak.weight
        drawCircle(
            brush = Brush.radialGradient(listOf(streak.color.copy(alpha = 0.9f), Color.Transparent), Offset(headX, y), glow),
            radius = glow,
            center = Offset(headX, y),
        )
        drawCircle(Color.White, radius = 2.2.dp.toPx() * streak.weight, center = Offset(headX, y))

        // Label rides just ahead of the head.
        drawText(
            streak.label,
            color = streak.color,
            topLeft = Offset(headX + 8.dp.toPx(), y - streak.label.size.height - 1.dp.toPx()),
            alpha = 0.95f,
        )
    }
}
