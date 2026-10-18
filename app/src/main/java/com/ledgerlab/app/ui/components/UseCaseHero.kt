package com.ledgerlab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono

/** Someone taking part in a use case, drawn as a bubble in the flow diagram. */
data class Actor(val name: String, val icon: ImageVector)

/**
 * The branded header that makes each use case read like its own app: a logo, the app's name and
 * tagline over a faint grid, then who's involved and what passes between them.
 */
@Composable
fun UseCaseHero(
    brand: String,
    tagline: String,
    icon: ImageVector,
    accent: Color,
    actors: List<Actor>,
    links: List<String>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), LabColors.Surface, LabColors.Background)))
            .border(1.dp, accent.copy(alpha = 0.35f), shape),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val step = 22.dp.toPx()
            var x = 0f
            while (x < size.width) {
                var y = 0f
                while (y < size.height) {
                    drawCircle(accent.copy(alpha = 0.10f), radius = 1.2f, center = Offset(x, y))
                    y += step
                }
                x += step
            }
            drawCircle(accent.copy(alpha = 0.10f), radius = size.minDimension * 0.55f, center = Offset(size.width * 0.95f, 0f))
            drawCircle(accent.copy(alpha = 0.18f), radius = size.minDimension * 0.30f, center = Offset(size.width * 0.95f, 0f), style = Stroke(1.5f))
        }
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = LabColors.Background, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(brand, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = LabColors.TextPrimary)
                    Text(tagline, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                }
            }
            if (actors.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                ActorFlow(actors, links, accent)
            }
        }
    }
}

/** Actors left to right with a labelled arrow between each pair. */
@Composable
fun ActorFlow(actors: List<Actor>, links: List<String>, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        actors.forEachIndexed { i, actor ->
            ActorBubble(actor, accent, Modifier.width(66.dp))
            if (i < actors.lastIndex) {
                Column(Modifier.weight(1f).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        links.getOrNull(i).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = accent,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    Arrow(accent, Modifier.fillMaxWidth().height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ActorBubble(actor: Actor, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(LabColors.SurfaceHigh).border(1.dp, accent.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(actor.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(actor.name, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = LabColors.TextPrimary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun Arrow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val y = size.height / 2
        val head = 6.dp.toPx()
        drawLine(color.copy(alpha = 0.7f), Offset(4f, y), Offset(size.width - 4f, y), strokeWidth = 1.5.dp.toPx())
        val path = Path().apply {
            moveTo(size.width - 4f, y)
            lineTo(size.width - 4f - head, y - head / 1.6f)
            lineTo(size.width - 4f - head, y + head / 1.6f)
            close()
        }
        drawPath(path, color)
    }
}

/** A numbered vertical walkthrough, each step ticked off once it's done. */
@Composable
fun StepGuide(steps: List<Pair<String, Boolean>>, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        steps.forEachIndexed { i, (text, done) ->
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .background(if (done) accent else LabColors.SurfaceHigh)
                            .border(1.dp, accent.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (done) "✓" else "${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (done) LabColors.Background else accent)
                    }
                    if (i < steps.lastIndex) Box(Modifier.width(1.5.dp).height(18.dp).background(accent.copy(alpha = 0.35f)))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done) LabColors.TextSecondary else LabColors.TextPrimary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
