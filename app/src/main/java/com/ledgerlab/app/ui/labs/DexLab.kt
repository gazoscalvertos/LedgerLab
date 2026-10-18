package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.AccountOffer
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.BookOffer
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.TrustLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.math.RoundingMode

data class DexState(
    val lines: List<TrustLine> = emptyList(),
    val myOffers: List<AccountOffer> = emptyList(),
    /** People selling the token for XRP (asks). */
    val asks: List<BookOffer> = emptyList(),
    /** People buying the token with XRP (bids). */
    val bids: List<BookOffer> = emptyList(),
)

class DexViewModel(app: Application) : LabViewModel(app) {
    private val _state = MutableStateFlow(DexState())
    val state: StateFlow<DexState> = _state.asStateFlow()

    var pair: Asset.Token? = null
        private set

    fun choosePair(token: Asset.Token?) {
        if (token == pair) return
        pair = token
        reload()
    }

    override suspend fun load() {
        val wallet = selected.value ?: return
        val q = queries()
        val lines = q.trustLines(wallet.address)
        val token = pair
        _state.value = DexState(
            lines = lines,
            myOffers = q.offers(wallet.address),
            asks = if (token != null) q.book(gets = token, pays = Asset.Xrp) else emptyList(),
            bids = if (token != null) q.book(gets = Asset.Xrp, pays = token) else emptyList(),
        )
    }

    /** Buy or sell [amount] of the token at [price] XRP each. */
    fun place(buy: Boolean, amount: String, price: String) {
        val label = if (buy) "Buy order" else "Sell order"
        val token = pair ?: return fail(label, "Pick a token pair first.")
        val qty = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter an amount.")
        val each = price.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter a price in XRP.")
        val xrp = (qty * each).setScale(6, RoundingMode.DOWN)
        if (xrp.signum() <= 0) return fail(label, "That order is worth less than one drop.")
        runTx("$label: $qty ${token.currency} @ $each XRP") { a, k, f ->
            if (buy) {
                LabTransactions.offerCreate(a, k, f, gets = Asset.Xrp.amount(xrp), pays = token.amount(qty))
            } else {
                LabTransactions.offerCreate(a, k, f, gets = token.amount(qty), pays = Asset.Xrp.amount(xrp))
            }
        }
    }

    fun cancel(offer: AccountOffer) = runTx("Cancel offer #${offer.sequence}") { a, k, f ->
        LabTransactions.offerCancel(a, k, f, offer.sequence)
    }
}

@Composable
fun DexLab(onBack: () -> Unit, vm: DexViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Cyan

    var code by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf("") }
    var buy by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("10") }
    var price by rememberSaveable { mutableStateOf("0.5") }
    val pair = tokenOrNull(code, issuer)
    LaunchedEffect(pair) { vm.choosePair(pair) }

    LabScaffold("DEX", "The order book built into the ledger", accent, network, vm::selectNetwork, onBack) {
        if (wallets.isEmpty()) {
            item { NeedsWallet(accent) }
        } else {
            item {
                Panel(accent = accent, title = "Trading as") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(12.dp))
                    TokenPicker(code, issuer, state.lines, accent) { c, i -> code = c; issuer = i }
                }
            }
            item { OrderBook(state, pair) }
            item {
                Panel(accent = accent, title = "Place an order") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabChip("Buy ${pair?.currency ?: "token"}", buy, LabColors.Matrix) { buy = true }
                        LabChip("Sell ${pair?.currency ?: "token"}", !buy, LabColors.Danger) { buy = false }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabTextField(amount, { amount = it }, "Amount", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                        LabTextField(price, { price = it }, "Price (XRP each)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                    }
                    Spacer(Modifier.height(10.dp))
                    LabButton(
                        if (buy) "Place buy offer" else "Place sell offer",
                        { vm.place(buy, amount, price) },
                        Modifier.fillMaxWidth(),
                        accent = if (buy) LabColors.Matrix else LabColors.Danger,
                        enabled = pair != null,
                    )
                    if (pair == null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Pick a token above. You need a trust line to it to buy.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item {
                Panel(accent = accent, title = "Your open offers") {
                    ErrorLine(error)
                    if (state.myOffers.isEmpty()) {
                        Text("No open offers.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    }
                    state.myOffers.forEach { offer ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Give ${Format.amount(offer.gets)}", fontFamily = Mono, color = LabColors.TextPrimary)
                                Text("for ${Format.amount(offer.pays)} · #${offer.sequence}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                            }
                            Text("Cancel", color = LabColors.Danger, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.cancel(offer) }.padding(6.dp))
                        }
                    }
                }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "The XRPL has had a decentralised exchange built in since 2012. Offers live on the ledger, not on a server.",
                    "OfferCreate says what you give (TakerGets) and what you want (TakerPays). The ratio is your price.",
                    "If an existing offer crosses yours it executes immediately; whatever is left rests on the book.",
                    "Each resting offer is an owned object, so it locks one owner reserve until it fills or is cancelled.",
                    "book_offers reads one side of the book. Asks give the token for XRP; bids give XRP for the token.",
                ),
                code = """
                    {
                      "TransactionType": "OfferCreate",
                      "Account": "rTrader…",
                      "TakerGets": "5000000",          // give 5 XRP
                      "TakerPays": {                   // want 10 tokens
                        "currency": "${pair?.currency ?: "LAB"}",
                        "issuer": "rIssuer…",
                        "value": "10"
                      }
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/tokens/decentralized-exchange",
            )
        }
    }
}

@Composable
private fun OrderBook(state: DexState, pair: Asset.Token?) {
    Panel(accent = LabColors.Cyan, title = if (pair != null) "${pair.currency} / XRP order book" else "Order book") {
        if (pair == null) {
            Text("Pick a token to see its book.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookSide("Bids", state.bids.map { priceOf(it.pays, it.gets) to it.pays }, LabColors.Matrix, Modifier.weight(1f))
            BookSide("Asks", state.asks.map { priceOf(it.gets, it.pays) to it.gets }, LabColors.Danger, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BookSide(title: String, rows: List<Pair<BigDecimal?, Amount?>>, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
        Spacer(Modifier.height(6.dp))
        if (rows.isEmpty()) Text("Empty", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        rows.forEach { (price, size) ->
            Row(Modifier.fillMaxWidth()) {
                Text(price?.toPlainString() ?: "?", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
                Text(Format.amount(size).substringBefore(' '), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary)
            }
        }
    }
}

/** XRP per token, given the token side and the XRP side of an offer. */
private fun priceOf(tokenSide: Amount?, xrpSide: Amount?): BigDecimal? {
    val tokens = (tokenSide as? Amount.Issued)?.value?.toBigDecimalOrNull() ?: return null
    val drops = (xrpSide as? Amount.Xrp)?.drops ?: return null
    if (tokens.signum() == 0) return null
    return BigDecimal(drops).movePointLeft(6).divide(tokens, 6, RoundingMode.HALF_UP).stripTrailingZeros()
}
