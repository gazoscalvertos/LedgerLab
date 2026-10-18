package com.ledgerlab.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.ledgerlab.xrpl.XrplNetwork

fun networkColor(network: XrplNetwork): Color = when (network) {
    XrplNetwork.TESTNET -> LabColors.Matrix
    XrplNetwork.DEVNET -> LabColors.Cyan
    XrplNetwork.MAINNET -> LabColors.Gold
}

/** Segmented pill control for Testnet / Devnet / Mainnet. */
@Composable
fun NetworkSwitcher(
    selected: XrplNetwork,
    onSelect: (XrplNetwork) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(LabColors.Surface)
            .border(1.dp, LabColors.Outline, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        XrplNetwork.entries.forEach { network ->
            val isSelected = network == selected
            val tint = networkColor(network)
            val bg by animateColorAsState(if (isSelected) tint.copy(alpha = 0.16f) else Color.Transparent, label = "net-bg")
            val fg by animateColorAsState(if (isSelected) tint else LabColors.TextSecondary, label = "net-fg")
            Row(
                Modifier
                    .clip(shape)
                    .background(bg)
                    .clickable { onSelect(network) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
                Spacer(Modifier.width(7.dp))
                Text(network.label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
