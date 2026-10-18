package com.ledgerlab.app.ui.labs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.Currencies
import com.ledgerlab.xrpl.wallet.WalletKeys

/** Picks a token, offering the tokens the saved wallets issue or hold as shortcuts. */
@Composable
fun TokenPicker(
    code: String,
    issuer: String,
    tokens: List<Asset.Token>,
    accent: Color,
    onChange: (code: String, issuer: String) -> Unit,
) {
    Column {
        if (tokens.isNotEmpty()) {
            Text("Your tokens", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                tokens.forEach { t ->
                    LabChip("${t.currency} · ${shortAddr(t.issuer)}", t.currency == code && t.issuer == issuer, accent) {
                        onChange(t.currency, t.issuer)
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

/**
 * Picks XRP or a token for an amount. [code] is blank for XRP. Shows the known tokens as chips, plus
 * a custom code and issuer when "Other" is chosen.
 */
@Composable
fun AssetPicker(
    code: String,
    issuer: String,
    tokens: List<Asset.Token>,
    accent: Color,
    onChange: (code: String, issuer: String) -> Unit,
) {
    var custom by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            LabChip("XRP", code.isBlank() && !custom, accent) { custom = false; onChange("", "") }
            tokens.forEach { t ->
                LabChip("${t.currency} · ${shortAddr(t.issuer)}", !custom && t.currency == code && t.issuer == issuer, accent) {
                    custom = false
                    onChange(t.currency, t.issuer)
                }
            }
            LabChip("Other token", custom, accent) { custom = true }
        }
        if (custom) {
            Spacer(Modifier.height(8.dp))
            LabTextField(code, { onChange(it.uppercase().take(20), issuer) }, "Token code", Modifier.fillMaxWidth(), accent, mono = true)
            Spacer(Modifier.height(8.dp))
            LabTextField(issuer, { onChange(code, it.trim()) }, "Issuer address", Modifier.fillMaxWidth(), accent, mono = true)
        }
    }
}

/** XRP when [code] is blank, a token when code and issuer are valid, otherwise null. */
fun assetOrNull(code: String, issuer: String): Asset? = if (code.isBlank()) Asset.Xrp else tokenOrNull(code, issuer)
