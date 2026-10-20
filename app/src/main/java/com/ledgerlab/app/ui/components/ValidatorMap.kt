package com.ledgerlab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.AmendmentStage
import com.ledgerlab.xrpl.AmendmentStatus
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.ValidatorInfo
import com.ledgerlab.xrpl.ValidatorSnapshot
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

fun agreementColor(agreement: Double?): Color = when {
    agreement == null -> LabColors.TextMuted
    agreement >= 0.98 -> LabColors.Matrix
    agreement >= 0.90 -> LabColors.Gold
    else -> LabColors.Danger
}

/**
 * The trusted validators as a constellation around the ledger they agree on. Nodes don't publish where they
 * are, so the layout is a ring rather than a world map: each dot is one validator, coloured by how often it
 * signed the same ledger as the network, with a live spoke while it's keeping up.
 */
@Composable
fun ValidatorMap(v: ValidatorSnapshot, modifier: Modifier = Modifier) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { nowMs = System.currentTimeMillis() } }
    val newest = v.validators.maxOfOrNull { it.lastLedger } ?: 0L
    val agreement = v.networkAgreement

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f)) {
                val c = center
                val radius = size.height * 0.40f
                val spin = (nowMs % 120_000L) / 120_000f * 2 * Math.PI.toFloat()
                // Faint orbit rings.
                for (r in listOf(0.45f, 0.75f, 1f, 1.2f)) {
                    drawCircle(LabColors.Cyan.copy(alpha = 0.06f), radius * r, c, style = Stroke(1f))
                }
                val n = v.validators.size
                v.validators.forEachIndexed { i, node ->
                    val angle = spin + i * 2 * Math.PI.toFloat() / n.coerceAtLeast(1)
                    val wobble = if (i % 2 == 0) 1f else 0.86f
                    val p = Offset(c.x + radius * wobble * cos(angle) * 1.3f, c.y + radius * wobble * sin(angle))
                    val color = agreementColor(node.agreement)
                    val live = newest > 0 && node.lastLedger >= newest - 1
                    if (live) {
                        drawLine(
                            Brush.linearGradient(listOf(color.copy(alpha = 0.05f), color.copy(alpha = 0.45f)), c, p),
                            c,
                            p,
                            strokeWidth = 1.2f,
                        )
                    }
                    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent), p, 9.dp.toPx()), 9.dp.toPx(), p)
                    drawCircle(if (live) color else color.copy(alpha = 0.4f), 3.5.dp.toPx(), p)
                }
                val pulse = (nowMs % 3_800L) / 3_800f
                drawCircle(LabColors.Cyan.copy(alpha = (1 - pulse) * 0.4f), radius * 0.2f + pulse * radius * 0.3f, c, style = Stroke(2f))
                drawCircle(Brush.radialGradient(listOf(LabColors.Cyan.copy(alpha = 0.35f), Color.Transparent), c, radius * 0.3f), radius * 0.3f, c)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    agreement?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "—",
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = agreementColor(agreement),
                )
                Text("agreement", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            }
        }
        Text(
            when {
                v.validators.isEmpty() -> "Listening for validations…"
                v.unlKnown -> "${v.unlSize} validators on the published list" +
                    (if (v.othersSeen > 0) ", plus ${v.othersSeen} others seen" else "") +
                    " · judged over ${v.ledgersTracked} ledgers"
                else -> "${v.validators.size} validators seen (couldn't read the published list) · ${v.ledgersTracked} ledgers"
            },
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
        )
        Legend()
        ValidatorList(v.validators)
        if (v.versions.isNotEmpty()) {
            Text("SOFTWARE VERSIONS", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            val reported = v.versions.sumOf { it.second }
            v.versions.forEachIndexed { i, (version, n) ->
                val color = if (i == 0) LabColors.Cyan else LabColors.Violet
                Column {
                    Row {
                        Text(version, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text("$n of $reported", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = color)
                    }
                    Spacer(Modifier.height(4.dp))
                    Bar(n.toFloat() / reported, color)
                }
            }
            Text(
                "Validators report their version every 256 ledgers (about 15 minutes), so this fills in as they do.",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
            )
        }
    }
}

/**
 * Every amendment the server knows: ones with a majority counting down to activation, ones being voted on,
 * ones nobody has voted for yet, and those already switched on.
 */
@Composable
fun AmendmentList(v: ValidatorSnapshot, modifier: Modifier = Modifier) {
    val nowMs = System.currentTimeMillis()
    var showEnabled by rememberSaveable { mutableStateOf(false) }
    val byStage = v.amendmentStatus.groupBy { it.stage }
    val defaultThreshold = Math.ceil(v.unlSize * 0.8).toInt()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (v.amendmentStatus.isEmpty()) {
            Text("Asking the server for its amendment list…", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StageCount("Enabled", byStage[AmendmentStage.ENABLED].orEmpty().size, LabColors.Matrix, Modifier.weight(1f))
            StageCount("Majority", byStage[AmendmentStage.MAJORITY].orEmpty().size, LabColors.Gold, Modifier.weight(1f))
            StageCount("Voting", byStage[AmendmentStage.VOTING].orEmpty().size, LabColors.Violet, Modifier.weight(1f))
            StageCount("No votes", byStage[AmendmentStage.WAITING].orEmpty().size, LabColors.TextSecondary, Modifier.weight(1f))
        }
        v.amendmentStatus.filter { it.stage == AmendmentStage.MAJORITY || it.stage == AmendmentStage.VOTING }
            .forEach { AmendmentRow(it, defaultThreshold, nowMs) }
        val waiting = byStage[AmendmentStage.WAITING].orEmpty()
        if (waiting.isNotEmpty()) {
            Text("NO VOTES YET", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Text(waiting.joinToString(" · ") { it.name }, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
        val enabled = byStage[AmendmentStage.ENABLED].orEmpty()
        if (enabled.isNotEmpty()) {
            Text(
                if (showEnabled) "Hide enabled" else "Show ${enabled.size} enabled",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.Cyan,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showEnabled = !showEnabled }.padding(vertical = 4.dp),
            )
            if (showEnabled) {
                Text(
                    enabled.sortedBy { it.name }.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextSecondary,
                )
            }
        }
        Text(
            "An amendment needs 80% of trusted validators for two weeks straight, then it switches on for everyone.",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextMuted,
        )
    }
}

@Composable
private fun StageCount(label: String, n: Int, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary, maxLines = 1)
        Text("$n", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(LabColors.Outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("≥98%" to LabColors.Matrix, "≥90%" to LabColors.Gold, "lower" to LabColors.Danger, "silent" to LabColors.TextMuted).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            }
        }
    }
}

@Composable
private fun ValidatorList(validators: List<ValidatorInfo>) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) validators else validators.take(6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { v ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(agreementColor(v.agreement)))
                Spacer(Modifier.width(10.dp))
                Text(
                    v.domain ?: Format.shortAddress(v.key),
                    fontFamily = if (v.domain == null) Mono else null,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                v.version?.let {
                    Text(it, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    v.agreement?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "silent",
                    fontFamily = Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = agreementColor(v.agreement),
                )
            }
        }
        if (validators.size > 6) {
            Text(
                if (all) "Show fewer" else "Show all ${validators.size}",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.Cyan,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { all = !all }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AmendmentRow(a: AmendmentStatus, defaultThreshold: Int, nowMs: Long) {
    val threshold = a.threshold ?: defaultThreshold
    val votes = a.votes ?: 0
    val fraction = if (threshold <= 0) 0f else votes.toFloat() / threshold
    val color = when (a.stage) {
        AmendmentStage.MAJORITY -> LabColors.Gold
        AmendmentStage.VOTING -> LabColors.Violet
        else -> LabColors.TextMuted
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!a.supported) {
                Text("not in this server's build  ", style = MaterialTheme.typography.bodySmall, color = LabColors.Orange)
            }
            Text(if (threshold > 0) "$votes/$threshold" else "$votes", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Bar(fraction, color)
        a.majoritySince?.let { since ->
            val enablesAtMs = (since + RIPPLE_EPOCH_SECONDS + TWO_WEEKS_SECONDS) * 1000
            val days = ((enablesAtMs - nowMs) / 86_400_000.0).coerceAtLeast(0.0)
            Text(
                if (days < 1) "Has a majority · switches on within a day" else String.format(Locale.US, "Has a majority · switches on in %.0f days", days),
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.Gold,
            )
        }
    }
}

private const val RIPPLE_EPOCH_SECONDS = 946_684_800L
private const val TWO_WEEKS_SECONDS = 14 * 86_400L
