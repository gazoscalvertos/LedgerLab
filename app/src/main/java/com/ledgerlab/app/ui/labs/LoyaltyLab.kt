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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabOutlineButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.labs.AccountFlags
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.Currencies
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.TrustLine
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xrpl.xrpl4j.model.transactions.AccountSet
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount
import java.math.BigDecimal

data class LoyaltyState(
    val shop: String? = null,
    val code: String = "PTS",
    val flags: AccountFlags? = null,
    /** The shop's view of every member's line. */
    val members: List<TrustLine> = emptyList(),
)

class LoyaltyViewModel(app: Application) : LabViewModel(app) {
    private val _state = MutableStateFlow(LoyaltyState())
    val state: StateFlow<LoyaltyState> = _state.asStateFlow()

    private var shopId: String? = null
    private var code = "PTS"

    fun shop(): StoredWallet? = wallets.value.firstOrNull { it.id == shopId } ?: wallets.value.firstOrNull()

    fun chooseShop(id: String) {
        if (id == shopId) return
        shopId = id
        reload()
    }

    fun chooseCode(input: String) {
        val c = input.trim().uppercase()
        if (!Currencies.isValid(c) || c == code) return
        code = c
        reload()
    }

    override suspend fun load() {
        val shop = shop() ?: return
        val flags = AppQueries(rpc(), XrplRpc(network.value.clioRpcUrl)).flags(shop.address)
        val members = queries().trustLines(shop.address).filter { it.issuedByMe && it.currency == code }
        _state.value = LoyaltyState(shop.address, code, flags, members)
    }

    private fun token(shop: StoredWallet) = Asset.Token(code, shop.address)

    /**
     * Turns on the shop's rules. RequireAuth and clawback can only be switched on while the account
     * has no trust lines yet, which is why this comes first.
     */
    fun setUp(approval: Boolean, clawback: Boolean) {
        val label = "Set up the programme"
        val shop = shop() ?: return fail(label, "Pick the shop's wallet.")
        val flags = _state.value.flags
        runWork(label) { step ->
            if (flags?.defaultRipple != true) submitAs(shop, "Allowing points to move between members", step) { a, k, f -> LabTransactions.enableRippling(a, k, f) }
            if (clawback && flags?.allowClawback != true) {
                submitAs(shop, "Enabling clawback", step) { a, k, f -> LabTransactions.accountSetFlag(a, k, f, AccountSet.AccountSetFlag.ALLOW_TRUSTLINE_CLAWBACK) }
            }
            if (approval && flags?.requireAuth != true) {
                submitAs(shop, "Requiring approval for new members", step) { a, k, f -> LabTransactions.accountSetFlag(a, k, f, AccountSet.AccountSetFlag.REQUIRE_AUTH) }
            }
            "${shop.label} is ready to issue $code."
        }
    }

    /** A customer opts in by trusting the shop's points. */
    fun join() {
        val label = "Join"
        val shop = shop() ?: return fail(label, "Pick the shop's wallet.")
        val me = selected.value?.takeIf { it.id != shop.id } ?: return fail(label, "Switch to a customer's wallet to join.")
        runTx("${me.label} joins ${shop.label}'s programme") { a, k, f -> LabTransactions.trustSet(a, k, f, token(shop), BigDecimal("1000000")) }
    }

    fun approve(member: TrustLine) {
        val shop = shop() ?: return
        val holder = member.holder ?: return
        runTx("Approve ${shortAddr(holder)}", wallet = shop) { a, k, f -> LabTransactions.authorizeHolder(a, k, f, holder, code) }
    }

    fun award(member: TrustLine, points: String) {
        val shop = shop() ?: return
        val holder = member.holder ?: return
        val value = points.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return fail("Award points", "Enter a number of points.")
        runTx("Award $value $code", wallet = shop) { a, k, f -> LabTransactions.payment(a, k, f, holder, token(shop).amount(value), memo = "loyalty: purchase") }
    }

    fun clawBack(member: TrustLine, points: String) {
        val shop = shop() ?: return
        val holder = member.holder ?: return
        val value = points.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return fail("Claw back", "Enter a number of points.")
        runTx("Claw back $value $code", wallet = shop) { a, k, f -> LabTransactions.clawback(a, k, f, holder, code, value) }
    }

    /** Spending points is paying them back to the shop, which burns them (an issuer can't hold its own token). */
    fun spend(points: String) {
        val shop = shop() ?: return
        val value = points.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return fail("Spend points", "Enter a number of points.")
        runTx("Spend $value $code") { a, k, f -> LabTransactions.payment(a, k, f, shop.address, token(shop).amount(value), memo = "loyalty: reward") }
    }

    /** A sell offer on the DEX: points for XRP. */
    fun sell(points: String, xrp: String) {
        val shop = shop() ?: return
        val value = points.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return fail("Sell points", "Enter a number of points.")
        val drops = Transactions.parseXrp(xrp) ?: return fail("Sell points", "Enter an XRP price.")
        runTx("Offer $value $code for ${preciseXrp(drops)}") { a, k, f -> LabTransactions.offerCreate(a, k, f, token(shop).amount(value), XrpCurrencyAmount.ofDrops(drops)) }
    }
}

private enum class LoyaltyTab(val label: String) { SHOP("Shop"), CUSTOMER("Customer") }

@Composable
fun LoyaltyLab(onBack: () -> Unit, vm: LoyaltyViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Violet
    var tab by rememberSaveable { mutableStateOf(LoyaltyTab.SHOP) }

    LabScaffold("Loyalty points", "A shop's own token: earn, spend, swap", accent, network, vm::selectNetwork, onBack) {
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "One wallet is the shop and another is a customer. Use a fresh wallet for the shop: approval and clawback can only be switched on before any trust lines exist.") }
        } else {
            item {
                Panel(accent = accent) {
                    Text("Acting as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoyaltyTab.entries.forEach { t -> LabChip(t.label, tab == t, LabColors.Gold) { tab = t } }
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            when (tab) {
                LoyaltyTab.SHOP -> item { ShopTab(vm, state, wallets, accent) }
                LoyaltyTab.CUSTOMER -> item { CustomerTab(vm, state, wallet, wallets, accent) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "Points are a token the shop issues. Customers opt in with a trust line, so nobody receives tokens they didn't ask for.",
                    "With RequireAuth on, the shop approves each member's line before points can flow, for a members-only programme.",
                    "With AllowTrustLineClawback on, the shop can take points back, for refunds or fraud. Both flags must be set before the first trust line.",
                    "Spending points means paying them back to the shop, which takes them out of circulation.",
                    "Points are a real token, so members can sell them on the DEX for XRP, and the shop can choose whether to support that market.",
                ),
                code = """
                    { "TransactionType": "Clawback", "Account": "rShop…",
                      "Amount": { "currency": "PTS", "issuer": "rCustomer…", "value": "50" } }
                    // for trust line tokens, "issuer" names the holder
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/tokens/fungible-tokens/clawing-back-tokens",
            )
        }
    }
}

@Composable
private fun ShopTab(vm: LoyaltyViewModel, state: LoyaltyState, wallets: List<StoredWallet>, accent: Color) {
    val shop = vm.shop()
    var code by rememberSaveable { mutableStateOf(state.code) }
    var approval by rememberSaveable { mutableStateOf(true) }
    var clawback by rememberSaveable { mutableStateOf(true) }
    var points by rememberSaveable { mutableStateOf("100") }
    val flags = state.flags
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "The shop") {
            WalletChips(wallets, shop, LabColors.Gold, { vm.chooseShop(it.id) })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(code, { code = it.uppercase().take(20) }, "Points code", Modifier.weight(1f), accent, mono = true)
                LabOutlineButton("Use", { vm.chooseCode(code) }, accent = accent)
            }
            Spacer(Modifier.height(6.dp))
            KeyValue("Approval required", if (flags?.requireAuth == true) "on" else "off")
            KeyValue("Clawback", if (flags?.allowClawback == true) "on" else "off")
            KeyValue("Members", "${state.members.size}")
            KeyValue("Points in circulation", state.members.sumOf { it.balance.toBigDecimalOrNull() ?: BigDecimal.ZERO }.stripTrailingZeros().toPlainString())
            if (flags?.defaultRipple != true || (approval && flags?.requireAuth != true) || (clawback && flags?.allowClawback != true)) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabChip("Approve members", approval, accent) { approval = !approval }
                    LabChip("Clawback", clawback, accent) { clawback = !clawback }
                }
                Spacer(Modifier.height(8.dp))
                LabButton("Set up the programme", { vm.setUp(approval, clawback) }, Modifier.fillMaxWidth(), accent)
                if (state.members.isNotEmpty()) {
                    Text("This shop already has members, so new approval or clawback rules will be rejected (tecOWNERS).", style = MaterialTheme.typography.bodySmall, color = LabColors.Gold)
                }
            }
        }
        Panel(accent = accent, title = "Members") {
            if (state.members.isEmpty()) Text("No members yet. Switch to a customer and join.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            LabTextField(points, { points = it }, "Points", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
            state.members.forEach { m ->
                val needsApproval = flags?.requireAuth == true && !m.authorized
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nameOf(m.holder, wallets), fontWeight = FontWeight.SemiBold, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text("${m.balance} ${state.code}", fontFamily = Mono, color = LabColors.Gold)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (needsApproval) {
                        LabButton("Approve", { vm.approve(m) }, Modifier.fillMaxWidth(), LabColors.Matrix)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabButton("Award", { vm.award(m, points) }, Modifier.weight(1f), LabColors.Matrix)
                            LabOutlineButton("Claw back", { vm.clawBack(m, points) }, Modifier.weight(1f), LabColors.Danger, enabled = flags?.allowClawback == true)
                        }
                    }
                }
            }
            LabOutlineButton("Refresh", vm::reload, Modifier.fillMaxWidth(), accent)
        }
    }
}

@Composable
private fun CustomerTab(vm: LoyaltyViewModel, state: LoyaltyState, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color) {
    val line = state.members.firstOrNull { it.holder == wallet?.address }
    var spend by rememberSaveable { mutableStateOf("20") }
    var sellPoints by rememberSaveable { mutableStateOf("10") }
    var sellXrp by rememberSaveable { mutableStateOf("1") }
    Panel(accent = accent, title = "${wallet?.label ?: "Customer"} at ${nameOf(state.shop, wallets)}") {
        when {
            wallet?.address == state.shop -> Text("You're the shop. Switch to a customer's wallet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            line == null -> {
                Text("Not a member yet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                LabButton("Join the programme", vm::join, Modifier.fillMaxWidth(), accent)
            }
            else -> {
                Text("${line.balance} ${state.code}", fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.Gold, style = MaterialTheme.typography.headlineSmall)
                if (state.flags?.requireAuth == true && !line.authorized) {
                    Text("Waiting for the shop to approve you.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LabTextField(spend, { spend = it }, "Points", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                    LabButton("Spend", { vm.spend(spend) }, Modifier.weight(1f), accent)
                }
                Spacer(Modifier.height(10.dp))
                Text("Swap on the DEX", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabTextField(sellPoints, { sellPoints = it }, "Points", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                    LabTextField(sellXrp, { sellXrp = it }, "For XRP", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                }
                Spacer(Modifier.height(6.dp))
                LabOutlineButton("Place sell offer", { vm.sell(sellPoints, sellXrp) }, Modifier.fillMaxWidth(), LabColors.Cyan)
            }
        }
    }
}
