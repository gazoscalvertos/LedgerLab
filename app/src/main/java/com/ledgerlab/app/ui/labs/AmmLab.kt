package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.AmmPool
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.TrustLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.math.RoundingMode

data class AmmState(
    val lines: List<TrustLine> = emptyList(),
    val pool: AmmPool? = null,
    val checked: Boolean = false,
    /** AMMCreate's fee is one owner reserve increment, read from the server. */
    val createFeeDrops: Long = 200_000,
)

class AmmViewModel(app: Application) : LabViewModel(app) {
    private val _state = MutableStateFlow(AmmState())
    val state: StateFlow<AmmState> = _state.asStateFlow()

    private var pair: Asset.Token? = null

    fun choosePair(token: Asset.Token?) {
        if (token == pair) return
        pair = token
        _state.value = _state.value.copy(pool = null, checked = false)
        reload()
    }

    override suspend fun load() {
        val wallet = selected.value ?: return
        val rpc = rpc()
        val q = queries()
        val token = pair
        _state.value = AmmState(
            lines = q.trustLines(wallet.address),
            pool = token?.let { q.amm(Asset.Xrp, it) },
            checked = token != null,
            createFeeDrops = runCatching { rpc.reserves().incrementDrops }.getOrDefault(200_000),
        )
    }

    fun create(xrp: String, tokens: String, feePercent: String) {
        val label = "Create AMM pool"
        val token = pair ?: return fail(label, "Pick a token first.")
        val x = xrp.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter the XRP side.")
        val t = tokens.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter the token side.")
        val fee = feePercent.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }
            ?: return fail(label, "The trading fee is between 0% and 1%.")
        val units = fee.movePointRight(3).setScale(0, RoundingMode.HALF_UP).toInt()
        val createFee = _state.value.createFeeDrops
        runTx(label) { a, k, f ->
            LabTransactions.ammCreate(a, k, f, Asset.Xrp.amount(x), token.amount(t), units, createFee)
        }
    }

    fun deposit(xrp: String, tokens: String) {
        val label = "Add liquidity"
        val token = pair ?: return fail(label, "Pick a token first.")
        val x = xrp.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter the XRP side.")
        val t = tokens.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return fail(label, "Enter the token side.")
        runTx(label) { a, k, f ->
            LabTransactions.ammDeposit(a, k, f, Asset.Xrp, token, Asset.Xrp.amount(x), token.amount(t))
        }
    }
}

@Composable
fun AmmLab(onBack: () -> Unit, vm: AmmViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Violet

    var code by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf("") }
    var xrp by rememberSaveable { mutableStateOf("50") }
    var tokens by rememberSaveable { mutableStateOf("50") }
    var fee by rememberSaveable { mutableStateOf("0.5") }
    val pair = tokenOrNull(code, issuer)
    LaunchedEffect(pair) { vm.choosePair(pair) }

    LabScaffold("AMM", "Automated market maker pools", accent, network, vm::selectNetwork, onBack) {
        if (wallets.isEmpty()) {
            item { NeedsWallet(accent) }
        } else {
            item {
                Panel(accent = accent, title = "Pool for XRP and…") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(12.dp))
                    TokenPicker(code, issuer, state.lines, accent) { c, i -> code = c; issuer = i }
                }
            }
            val pool = state.pool
            if (pair != null && state.checked) {
                item {
                    if (pool != null) PoolCard(pool, pair) else Panel(accent = accent, title = "No pool yet") {
                        Text(
                            "Nobody has created an XRP/${pair.currency} pool on ${network.label}. Be the first: you set the starting price.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LabColors.TextSecondary,
                        )
                    }
                }
                item {
                    Panel(accent = accent, title = if (pool == null) "Create the pool" else "Add liquidity") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabTextField(xrp, { xrp = it }, "XRP", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                            LabTextField(tokens, { tokens = it }, pair.currency, Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                        }
                        if (pool == null) {
                            Spacer(Modifier.height(8.dp))
                            LabTextField(fee, { fee = it }, "Trading fee (%)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Creating a pool costs ${Format.xrp(state.createFeeDrops)} as its transaction fee.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LabColors.TextMuted,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LabButton(
                            if (pool == null) "Create pool" else "Deposit both assets",
                            { if (pool == null) vm.create(xrp, tokens, fee) else vm.deposit(xrp, tokens) },
                            Modifier.fillMaxWidth(),
                            accent,
                        )
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "The XRPL's AMM (XLS-30) is built into the protocol, beside the order book DEX.",
                    "A pool holds two assets and prices them by the constant-product rule: x × y stays the same during a swap.",
                    "Depositors receive LP tokens representing their share. The pool's own account issues them.",
                    "LP token holders can vote on the trading fee (up to 1%), weighted by their share.",
                    "Payments and offers automatically route through whichever is cheaper: the AMM or the order book.",
                ),
                code = """
                    {
                      "TransactionType": "AMMCreate",
                      "Account": "rCreator…",
                      "Amount": "${(xrp.toBigDecimalOrNull() ?: BigDecimal.TEN).movePointRight(6).toBigInteger()}",
                      "Amount2": {
                        "currency": "${pair?.currency ?: "LAB"}",
                        "issuer": "rIssuer…",
                        "value": "$tokens"
                      },
                      "TradingFee": ${(fee.toBigDecimalOrNull() ?: BigDecimal("0.5")).movePointRight(3).toInt()}
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/tokens/decentralized-exchange/automated-market-makers",
            )
        }
    }
}

@Composable
private fun PoolCard(pool: AmmPool, token: Asset.Token) {
    val drops = (pool.amount1 as? Amount.Xrp)?.drops ?: (pool.amount2 as? Amount.Xrp)?.drops
    val tokenAmount = ((pool.amount2 as? Amount.Issued) ?: (pool.amount1 as? Amount.Issued))?.value?.toBigDecimalOrNull()
    val price = if (drops != null && tokenAmount != null && tokenAmount.signum() > 0) {
        BigDecimal(drops).movePointLeft(6).divide(tokenAmount, 6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    } else {
        "?"
    }
    Panel(accent = LabColors.Violet, title = "XRP / ${token.currency} pool") {
        KeyValue("XRP in pool", drops?.let(Format::xrp) ?: "?")
        KeyValue("${token.currency} in pool", tokenAmount?.stripTrailingZeros()?.toPlainString() ?: "?")
        KeyValue("Price", "$price XRP per ${token.currency}", LabColors.Violet)
        KeyValue("LP tokens issued", Format.amount(pool.lpToken).substringBefore(' '))
        KeyValue("Trading fee", "${BigDecimal(pool.tradingFee).movePointLeft(3).stripTrailingZeros().toPlainString()}%")
        KeyValue("Pool account", shortAddr(pool.account))
    }
}
