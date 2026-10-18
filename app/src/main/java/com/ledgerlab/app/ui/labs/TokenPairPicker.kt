package com.ledgerlab.app.ui.labs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.Currencies
import com.ledgerlab.xrpl.labs.TrustLine
import com.ledgerlab.xrpl.wallet.WalletKeys

/** Picks the token side of an XRP/token pair, offering the wallet's trust lines as shortcuts. */
@Composable
fun TokenPicker(
    code: String,
    issuer: String,
    lines: List<TrustLine>,
    accent: Color,
    onChange: (code: String, issuer: String) -> Unit,
) {
    Column {
        if (lines.isNotEmpty()) {
            Text("Your tokens", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                lines.forEach { line ->
                    LabChip("${line.currency} · ${shortAddr(line.issuer)}", line.currency == code && line.issuer == issuer, accent) {
                        onChange(line.currency, line.issuer)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LabTextField(code, { onChange(it.uppercase().take(20), issuer) }, "Token code", Modifier.fillMaxWidth(), accent, mono = true)
        Spacer(Modifier.height(8.dp))
        LabTextField(issuer, { onChange(code, it.trim()) }, "Issuer address", Modifier.fillMaxWidth(), accent, mono = true)
    }
}

fun tokenOrNull(code: String, issuer: String): Asset.Token? =
    if (Currencies.isValid(code) && WalletKeys.isValidAddress(issuer)) Asset.Token(code.trim(), issuer.trim()) else null
