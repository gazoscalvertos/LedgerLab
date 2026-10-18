package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.Currencies
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.TrustLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

class TokensViewModel(app: Application) : LabViewModel(app) {
    private val _lines = MutableStateFlow<List<TrustLine>>(emptyList())
    /** Trust lines of the selected (holder) wallet. */
    val lines: StateFlow<List<TrustLine>> = _lines.asStateFlow()

    override suspend fun load() {
        val holder = selected.value ?: return
        _lines.value = queries().trustLines(holder.address)
    }

    fun enableRippling(issuer: StoredWallet) = runTx("Enable rippling on ${issuer.label}", issuer) { a, k, f ->
        LabTransactions.enableRippling(a, k, f)
    }

    fun trust(holder: StoredWallet, issuer: StoredWallet, code: String, limit: String) {
        val label = "${holder.label} trusts ${issuer.label}"
        val token = token(code, issuer) ?: return fail(label, "Use a 3 letter code (not XRP) or a 4 to 20 character name.")
        val max = limit.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter a positive limit.")
        runTx(label, holder) { a, k, f -> LabTransactions.trustSet(a, k, f, token, max) }
    }

    fun issue(issuer: StoredWallet, holder: StoredWallet, code: String, amount: String) {
        val label = "${issuer.label} issues $code"
        val token = token(code, issuer) ?: return fail(label, "Check the token code.")
        val value = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter a positive amount.")
        runTx(label, issuer) { a, k, f -> LabTransactions.payment(a, k, f, holder.address, token.amount(value)) }
    }

    private fun token(code: String, issuer: StoredWallet): Asset.Token? =
        if (Currencies.isValid(code)) Asset.Token(code.trim(), issuer.address) else null
}

@Composable
fun TokensLab(onBack: () -> Unit, vm: TokensViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val holder by vm.selected.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Gold

    var issuerId by rememberSaveable { mutableStateOf<String?>(null) }
    val issuer = wallets.firstOrNull { it.id == issuerId && it.id != holder?.id } ?: wallets.firstOrNull { it.id != holder?.id }
    var code by rememberSaveable { mutableStateOf("LAB") }
    var limit by rememberSaveable { mutableStateOf("1000000") }
    var amount by rememberSaveable { mutableStateOf("100") }

    LabScaffold("Tokens", "Issue your own currency with trust lines", accent, network, vm::selectNetwork, onBack) {
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "Issuing a token takes two wallets: an issuer and a holder. Create and fund two in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent, title = "Who's who") {
                    Text("Holder", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets, holder, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Text("Issuer", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets.filter { it.id != holder?.id }, issuer, LabColors.Orange, { issuerId = it.id })
                    Spacer(Modifier.height(10.dp))
                    LabTextField(code, { code = it.uppercase().take(20) }, "Token code", Modifier.fillMaxWidth(), accent, mono = true)
                }
            }
            val h = holder
            if (h != null && issuer != null) {
                item {
                    Step(1, "Let the issuer's tokens ripple", "AccountSet with asfDefaultRipple, so holders can pay each other.") {
                        LabButton("Enable rippling", { vm.enableRippling(issuer) }, Modifier.fillMaxWidth(), accent)
                    }
                }
                item {
                    Step(2, "Holder opens a trust line", "TrustSet says how much of the issuer's $code the holder is willing to hold.") {
                        LabTextField(limit, { limit = it }, "Limit", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                        Spacer(Modifier.height(8.dp))
                        LabButton("Create trust line", { vm.trust(h, issuer, code, limit) }, Modifier.fillMaxWidth(), accent)
                    }
                }
                item {
                    Step(3, "Issuer sends tokens", "A Payment of $code from the issuer creates the tokens out of thin air.") {
                        LabTextField(amount, { amount = it }, "Amount of $code", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                        Spacer(Modifier.height(8.dp))
                        LabButton("Issue $code", { vm.issue(issuer, h, code, amount) }, Modifier.fillMaxWidth(), accent)
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item {
                Panel(accent = accent, title = "${holder?.label ?: "Holder"}'s trust lines") {
                    ErrorLine(error)
                    if (lines.isEmpty()) {
                        Text("No trust lines yet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lines.forEach { line ->
                            Row {
                                Column(Modifier.weight(1f)) {
                                    Text(line.currency, fontWeight = FontWeight.Bold, color = accent)
                                    Text("issuer ${shortAddr(line.issuer)}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                                }
                                Column {
                                    Text(line.balance, fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
                                    Text("limit ${line.limit}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "Any account can issue a token. Its identity is the pair (currency code, issuer address).",
                    "Holders must opt in with a trust line, which caps how much they'll hold and costs one owner reserve.",
                    "The issuer's balance on the line is negative: tokens are an IOU from the issuer to the holder.",
                    "Default Ripple lets balances flow between holders through the issuer, which the DEX and payments rely on.",
                    "Codes other than 3 letters are stored as 40 hex characters, which is why some tokens look like gibberish on explorers.",
                ),
                code = """
                    {
                      "TransactionType": "TrustSet",
                      "Account": "rHolder…",
                      "LimitAmount": {
                        "currency": "${code.ifBlank { "LAB" }}",
                        "issuer": "rIssuer…",
                        "value": "$limit"
                      }
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/tokens",
            )
        }
    }
}

@Composable
fun Step(number: Int, title: String, body: String, content: @Composable () -> Unit) {
    Panel(accent = LabColors.Gold) {
        Row {
            Text("$number", fontFamily = Mono, fontWeight = FontWeight.Black, color = LabColors.Gold)
            Spacer(Modifier.height(0.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = LabColors.TextPrimary)
                Text(body, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}
