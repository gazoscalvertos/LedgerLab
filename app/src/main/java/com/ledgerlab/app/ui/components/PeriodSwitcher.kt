package com.ledgerlab.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.xrpl.StatsPeriod

/** Segmented pill control for the dashboard's stats window: Live, 1h, 24h, 7d. */
@Composable
fun PeriodSwitcher(
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LabColors.Surface)
            .border(1.dp, LabColors.Outline, shape)
            .padding(4.dp),
    ) {
        StatsPeriod.entries.forEach { period ->
            val isSelected = period == selected
            val bg by animateColorAsState(if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent, label = "period-bg")
            val fg by animateColorAsState(if (isSelected) accent else LabColors.TextSecondary, label = "period-fg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(bg)
                    .clickable { onSelect(period) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(period.label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
