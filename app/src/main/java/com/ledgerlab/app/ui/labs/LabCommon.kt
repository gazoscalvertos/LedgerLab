package com.ledgerlab.app.ui.labs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.XrplNetwork

/** Horizontal chips to choose which saved wallet a lab acts as. */
@Composable
fun WalletChips(
    wallets: List<StoredWallet>,
    selected: StoredWallet?,
    accent: Color,
    onSelect: (StoredWallet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        wallets.forEach { w -> LabChip(w.label, w.id == selected?.id, accent) { onSelect(w) } }
    }
}

@Composable
fun LabChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val color = if (selected) accent else LabColors.TextSecondary
    Text(
        text,
        color = color,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.15f) else LabColors.Surface)
            .border(1.dp, if (selected) accent.copy(alpha = 0.5f) else LabColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Shown when a lab needs a wallet and there isn't one yet. */
@Composable
fun NeedsWallet(accent: Color, message: String = "This lab signs transactions, so it needs a funded wallet. Create one in the Wallet lab first.") {
    Panel(accent = accent, title = "Wallet needed") {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextSecondary)
    }
}

@Composable
fun ErrorLine(error: String?) {
    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = LabColors.Danger)
    }
}

/** Progress and result of the lab's latest transaction, with an explorer link. */
@Composable
fun TxStatusCard(status: TxStatus, network: XrplNetwork, onClear: () -> Unit) {
    if (status == TxStatus.Idle) return
    val uri = LocalUriHandler.current
    val (color, title) = when (status) {
        is TxStatus.Working -> LabColors.Cyan to status.label
        is TxStatus.Done -> (if (status.outcome.success) LabColors.Matrix else LabColors.Gold) to status.label
        is TxStatus.Failed -> LabColors.Danger to status.label
        TxStatus.Idle -> return
    }
    Panel(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status is TxStatus.Working) {
                CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = LabColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    when (status) {
                        is TxStatus.Working -> status.step + "…"
                        is TxStatus.Done -> if (status.outcome.success) "Validated: tesSUCCESS" else status.outcome.result
                        is TxStatus.Failed -> status.message
                        TxStatus.Idle -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
            if (status !is TxStatus.Working) {
                Text("✕", color = LabColors.TextMuted, modifier = Modifier.clickable(onClick = onClear).padding(6.dp))
            }
        }
        val hash = (status as? TxStatus.Done)?.outcome?.hash
        if (hash != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "View ${hash.take(10)}… on the explorer",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.Cyan,
                modifier = Modifier.clickable { uri.openUri("${network.explorerUrl}/transactions/$hash") },
            )
        }
    }
}

/** A label / monospace value row used in lab readouts. */
@Composable
fun KeyValue(label: String, value: String, valueColor: Color = LabColors.TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Mono, fontSize = 13.sp, color = valueColor)
    }
}

fun shortAddr(address: String) = Format.shortAddress(address)
