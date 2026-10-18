package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.app.ui.components.Actor
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabOutlineButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.components.StepGuide
import com.ledgerlab.app.ui.components.UseCaseHero
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.AccountFlags
import com.ledgerlab.xrpl.labs.AccountOffer
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.BookOffer
import com.ledgerlab.xrpl.labs.Currencies
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.TrustLine
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.XrplRpc
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xrpl.xrpl4j.model.transactions.AccountSet
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount

data class LoyaltyState(
    val shop: String? = null,
    val code: String = "PTS",
    val flags: AccountFlags? = null,
    /** The shop's view of every member's line. */
    val members: List<TrustLine> = emptyList(),
    /** Members selling points for XRP, best price first. */
    val asks: List<BookOffer> = emptyList(),
    /** Members offering XRP for points, best price first. */
    val bids: List<BookOffer> = emptyList(),
    /** The selected wallet's own open offers on this market. */
    val myOffers: List<AccountOffer> = emptyList(),
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
        val q = queries()
        val members = q.trustLines(shop.address).filter { it.issuedByMe && it.currency == code }
        val points = token(shop)
        val asks = runCatching { q.book(points, Asset.Xrp, limit = 12) }.getOrDefault(emptyList())
        val bids = runCatching { q.book(Asset.Xrp, points, limit = 12) }.getOrDefault(emptyList())
        val me = selected.value?.address
        val mine = if (me == null) emptyList() else runCatching { q.offers(me) }.getOrDefault(emptyList())
            .filter { o -> listOf(o.gets, o.pays).any { (it as? Amount.Issued)?.let { a -> a.currency == code && a.issuer == shop.address } == true } }
        _state.value = LoyaltyState(shop.address, code, flags, members, asks, bids, mine)
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

    /** Takes a member's sell offer in full: pay its XRP, receive its points. Needs a trust line to hold them. */
    fun buy(ask: BookOffer) {
        val shop = shop() ?: return
        val points = (ask.gets as? Amount.Issued)?.value?.toBigDecimalOrNull() ?: return fail("Buy points", "Couldn't read that offer.")
        val drops = (ask.pays as? Amount.Xrp)?.drops ?: return fail("Buy points", "Couldn't read that offer.")
        if (selected.value?.address == ask.account) return fail("Buy points", "That's your own offer.")
        if (_state.value.members.none { it.holder == selected.value?.address }) return fail("Buy points", "Join the programme first, so you have somewhere to hold the points.")
        runTx("Buy ${points.stripTrailingZeros().toPlainString()} $code for ${preciseXrp(drops)}") { a, k, f ->
            LabTransactions.offerCreate(a, k, f, XrpCurrencyAmount.ofDrops(drops), token(shop).amount(points))
        }
    }

    /** Fills a member's bid: give them points, receive their XRP. */
    fun sellTo(bid: BookOffer) {
        val shop = shop() ?: return
        val points = (bid.pays as? Amount.Issued)?.value?.toBigDecimalOrNull() ?: return fail("Sell points", "Couldn't read that offer.")
        val drops = (bid.gets as? Amount.Xrp)?.drops ?: return fail("Sell points", "Couldn't read that offer.")
        if (selected.value?.address == bid.account) return fail("Sell points", "That's your own offer.")
        runTx("Sell ${points.stripTrailingZeros().toPlainString()} $code for ${preciseXrp(drops)}") { a, k, f ->
            LabTransactions.offerCreate(a, k, f, token(shop).amount(points), XrpCurrencyAmount.ofDrops(drops))
        }
    }

    /** A standing offer to buy points with XRP. */
    fun bid(points: String, xrp: String) {
        val shop = shop() ?: return
        val value = points.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return fail("Bid for points", "Enter a number of points.")
        val drops = Transactions.parseXrp(xrp) ?: return fail("Bid for points", "Enter an XRP price.")
        runTx("Bid ${preciseXrp(drops)} for $value $code") { a, k, f -> LabTransactions.offerCreate(a, k, f, XrpCurrencyAmount.ofDrops(drops), token(shop).amount(value)) }
    }

    fun cancelOffer(offer: AccountOffer) = runTx("Cancel offer") { a, k, f -> LabTransactions.offerCancel(a, k, f, offer.sequence) }

    /** A sell offer on the DEX: points for XRP. */
    fun sell(points: String, xrp: String) {
        val shop = shop() ?: return
        val value = points.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return fail("Sell points", "Enter a number of points.")
        val drops = Transactions.parseXrp(xrp) ?: return fail("Sell points", "Enter an XRP price.")
        runTx("Offer $value $code for ${preciseXrp(drops)}") { a, k, f -> LabTransactions.offerCreate(a, k, f, token(shop).amount(value), XrpCurrencyAmount.ofDrops(drops)) }
    }
}

private enum class LoyaltyTab(val label: String) { SHOP("I'm the shop"), CUSTOMER("I'm a customer"), MARKET("Market") }

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

    LabScaffold("Loyalty points", "A shop's own token: earn, spend, swap", accent, network, vm::selectNetwork, onBack, features = listOf("Tokens", "RequireAuth", "Clawback", "DEX")) {
        item {
            UseCaseHero(
                brand = "Perks",
                tagline = "Points you earn, spend and trade",
                icon = Icons.Outlined.Loyalty,
                accent = accent,
                actors = listOf(Actor("Shop", Icons.Outlined.Storefront), Actor("Customer", Icons.Outlined.Person), Actor("DEX", Icons.Outlined.CandlestickChart)),
                links = listOf("issues points", "trades them"),
            )
        }
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "One wallet is the shop and another is a customer. Use a fresh wallet for the shop: approval and clawback can only be switched on before any trust lines exist.") }
        } else {
            item {
                Panel(accent = accent) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoyaltyTab.entries.forEach { t -> LabChip(t.label, tab == t, LabColors.Gold) { tab = t } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (tab) {
                            LoyaltyTab.SHOP -> "Run the programme: pick the shop wallet, set its rules, approve members and award points."
                            LoyaltyTab.CUSTOMER -> "Use the programme as a customer: join, collect points and spend them."
                            LoyaltyTab.MARKET -> "Points are a real token, so members can trade them with each other for XRP."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                    if (tab != LoyaltyTab.SHOP) {
                        Spacer(Modifier.height(10.dp))
                        Text("Acting as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                        WalletChips(wallets.filter { it.address != state.shop }, wallet, accent, { vm.selectWallet(it.id) })
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            when (tab) {
                LoyaltyTab.SHOP -> item { ShopTab(vm, state, wallets, accent) }
                LoyaltyTab.CUSTOMER -> item { CustomerTab(vm, state, wallet, wallets, accent) { tab = LoyaltyTab.MARKET } }
                LoyaltyTab.MARKET -> item { MarketTab(vm, state, wallet, wallets, accent) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "Points are a token the shop issues. Customers opt in with a trust line, so nobody receives tokens they didn't ask for.",
                    "With RequireAuth on, the shop approves each member's line before points can flow, for a members-only programme.",
                    "With AllowTrustLineClawback on, the shop can take points back, for refunds or fraud. Both flags must be set before the first trust line.",
                    "Spending points means paying them back to the shop, which takes them out of circulation.",
                    "Points are a real token, so members can trade them on the DEX: a sell offer sits in the order book until someone buys it.",
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

private fun circulation(state: LoyaltyState) =
    state.members.sumOf { it.balance.toBigDecimalOrNull() ?: BigDecimal.ZERO }.stripTrailingZeros().toPlainString()

@Composable
private fun ShopTab(vm: LoyaltyViewModel, state: LoyaltyState, wallets: List<StoredWallet>, accent: Color) {
    val shop = vm.shop()
    var code by rememberSaveable { mutableStateOf(state.code) }
    var approval by rememberSaveable { mutableStateOf(true) }
    var clawback by rememberSaveable { mutableStateOf(true) }
    var points by rememberSaveable { mutableStateOf("100") }
    val flags = state.flags
    val setUp = flags?.defaultRipple == true
    val pending = state.members.count { flags?.requireAuth == true && !it.authorized }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "Your progress") {
            StepGuide(
                listOf(
                    "Set the programme's rules" to setUp,
                    "Customers join (switch to I'm a customer)" to state.members.isNotEmpty(),
                    "Approve new members" to (state.members.isNotEmpty() && pending == 0),
                    "Award points for purchases" to (circulation(state) != "0"),
                ),
                accent,
            )
        }
        Panel(accent = accent, title = "1 · Programme rules") {
            Text("Shop wallet", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            WalletChips(wallets, shop, LabColors.Gold, { vm.chooseShop(it.id) })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(code, { code = it.uppercase().take(20) }, "Points code", Modifier.weight(1f), accent, mono = true)
                LabOutlineButton("Use", { vm.chooseCode(code) }, accent = accent)
            }
            Spacer(Modifier.height(10.dp))
            RuleRow("Members need approval", "You approve each customer before they can hold points.", flags?.requireAuth == true, approval, !setUp) { approval = it }
            RuleRow("Clawback", "You can take points back, for refunds or fraud.", flags?.allowClawback == true, clawback, !setUp) { clawback = it }
            if (!setUp) {
                Spacer(Modifier.height(10.dp))
                LabButton("Save the rules", { vm.setUp(approval, clawback) }, Modifier.fillMaxWidth(), accent)
                if (state.members.isNotEmpty()) {
                    Text("This shop already has members, so approval and clawback can't be added now (tecOWNERS).", style = MaterialTheme.typography.bodySmall, color = LabColors.Gold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("Members", "${state.members.size}", accent, Modifier.weight(1f))
            StatBox("Awaiting approval", "$pending", LabColors.Gold, Modifier.weight(1f))
            StatBox("In circulation", circulation(state), LabColors.Matrix, Modifier.weight(1f))
        }
        Panel(accent = accent, title = "2 · Members") {
            if (state.members.isEmpty()) {
                Text("No members yet. Switch to I'm a customer and join.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            } else {
                LabTextField(points, { points = it }, "Points to award or claw back", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
            }
            state.members.forEach { m ->
                val needsApproval = flags?.requireAuth == true && !m.authorized
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(m.holder, wallets), fontWeight = FontWeight.SemiBold, color = LabColors.TextPrimary)
                            Text(if (needsApproval) "waiting for approval" else "member", style = MaterialTheme.typography.bodySmall, color = if (needsApproval) LabColors.Gold else LabColors.TextMuted)
                        }
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
private fun RuleRow(title: String, detail: String, on: Boolean, wanted: Boolean, editable: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LabColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
        }
        if (editable) LabChip(if (wanted) "On" else "Off", wanted, LabColors.Violet) { onChange(!wanted) }
        else Text(if (on) "ON" else "OFF", style = MaterialTheme.typography.labelSmall, color = if (on) LabColors.Matrix else LabColors.TextMuted)
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(LabColors.Surface).padding(12.dp)) {
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted, maxLines = 2)
    }
}

/** The member's points shown as a loyalty card. */
@Composable
private fun LoyaltyCard(shopName: String, member: String, balance: String, code: String, accent: Color) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().aspectRatio(1.7f).clip(shape)
            .background(Brush.linearGradient(listOf(accent, LabColors.Magenta.copy(alpha = 0.7f), LabColors.Background)))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Loyalty, contentDescription = null, tint = LabColors.TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Perks · $shopName", fontWeight = FontWeight.Bold, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
            Text("MEMBER", style = MaterialTheme.typography.labelSmall, color = LabColors.TextPrimary)
        }
        Spacer(Modifier.weight(1f))
        Text(balance, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = LabColors.TextPrimary)
        Text("$code points", style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))
        Text(member, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary.copy(alpha = 0.8f))
    }
}

@Composable
private fun CustomerTab(vm: LoyaltyViewModel, state: LoyaltyState, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color, openMarket: () -> Unit) {
    val line = state.members.firstOrNull { it.holder == wallet?.address }
    val approved = line != null && (state.flags?.requireAuth != true || line.authorized)
    val hasPoints = (line?.balance?.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
    var spend by rememberSaveable { mutableStateOf("20") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (wallet?.address == state.shop) {
            Panel(accent = accent) { Text("You're acting as the shop. Pick a customer's wallet above.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted) }
            return@Column
        }
        Panel(accent = accent, title = "Your progress") {
            StepGuide(
                listOf(
                    "Join ${nameOf(state.shop, wallets)}'s programme" to (line != null),
                    "Get approved by the shop" to approved,
                    "Earn points (the shop awards them)" to hasPoints,
                    "Spend them, or trade them on the market" to false,
                ),
                accent,
            )
        }
        if (line == null) {
            Panel(accent = accent, title = "Join") {
                Text("Joining adds a trust line for ${state.code}: your wallet agrees to hold the shop's points.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                LabButton("Join the programme", vm::join, Modifier.fillMaxWidth(), accent)
            }
        } else {
            LoyaltyCard(nameOf(state.shop, wallets), wallet?.label ?: "", line.balance, state.code, accent)
            if (!approved) Text("Waiting for the shop to approve you. Switch to I'm the shop to approve.", style = MaterialTheme.typography.bodySmall, color = LabColors.Gold)
            Panel(accent = accent, title = "Spend points") {
                Text("Paying points back to the shop redeems them for a reward.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LabTextField(spend, { spend = it }, "Points", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                    LabButton("Redeem", { vm.spend(spend) }, Modifier.weight(1f), accent, enabled = hasPoints)
                }
                Spacer(Modifier.height(8.dp))
                LabOutlineButton("Trade on the market", openMarket, Modifier.fillMaxWidth(), LabColors.Cyan)
            }
        }
    }
}

@Composable
private fun MarketTab(vm: LoyaltyViewModel, state: LoyaltyState, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color) {
    var sellPoints by rememberSaveable { mutableStateOf("10") }
    var sellXrp by rememberSaveable { mutableStateOf("1") }
    var bidPoints by rememberSaveable { mutableStateOf("10") }
    var bidXrp by rememberSaveable { mutableStateOf("0.8") }
    val isMember = state.members.any { it.holder == wallet?.address }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!isMember) Text("Join the programme as this wallet first: you need a trust line to hold points you buy.", style = MaterialTheme.typography.bodySmall, color = LabColors.Gold)
        Panel(accent = LabColors.Matrix, title = "For sale (${state.asks.size})") {
            if (state.asks.isEmpty()) Text("No one is selling ${state.code} yet. Place a sell offer below.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            state.asks.forEach { o ->
                OfferRow(o, wallets, wallet, "Buy", LabColors.Matrix, sell = true) { vm.buy(o) }
            }
        }
        Panel(accent = LabColors.Cyan, title = "Wanted (${state.bids.size})") {
            if (state.bids.isEmpty()) Text("No bids yet. Place one below.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            state.bids.forEach { o ->
                OfferRow(o, wallets, wallet, "Sell", LabColors.Cyan, sell = false) { vm.sellTo(o) }
            }
        }
        Panel(accent = accent, title = "Place an offer") {
            Text("Sell points", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(sellPoints, { sellPoints = it }, state.code, Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                LabTextField(sellXrp, { sellXrp = it }, "for XRP", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                LabButton("Sell", { vm.sell(sellPoints, sellXrp) }, accent = LabColors.Matrix)
            }
            Spacer(Modifier.height(10.dp))
            Text("Buy points", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(bidPoints, { bidPoints = it }, state.code, Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                LabTextField(bidXrp, { bidXrp = it }, "for XRP", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                LabButton("Bid", { vm.bid(bidPoints, bidXrp) }, accent = LabColors.Cyan)
            }
            Text("If an offer crosses one already in the book, it fills straight away.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
        if (state.myOffers.isNotEmpty()) {
            Panel(accent = accent, title = "Your open offers") {
                state.myOffers.forEach { o ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${Format.amount(o.gets)} for ${Format.amount(o.pays)}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
                        LabOutlineButton("Cancel", { vm.cancelOffer(o) }, accent = LabColors.Danger)
                    }
                }
            }
        }
        LabOutlineButton("Refresh", vm::reload, Modifier.fillMaxWidth(), accent)
    }
}

@Composable
private fun OfferRow(o: BookOffer, wallets: List<StoredWallet>, wallet: StoredWallet?, action: String, color: Color, sell: Boolean, onTake: () -> Unit) {
    val points = if (sell) o.gets else o.pays
    val xrp = if (sell) o.pays else o.gets
    val pts = (points as? Amount.Issued)?.value?.toDoubleOrNull()
    val drops = (xrp as? Amount.Xrp)?.drops
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${Format.amount(points)} for ${Format.amount(xrp)}", fontFamily = Mono, fontWeight = FontWeight.SemiBold, color = LabColors.TextPrimary)
            Text(
                (if (pts != null && drops != null && pts > 0) "${preciseXrp((drops / pts).toLong())} per point · " else "") + "by ${nameOf(o.account, wallets)}",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
        }
        if (o.account == wallet?.address) Text("yours", style = MaterialTheme.typography.labelSmall, color = LabColors.TextMuted)
        else LabButton(action, onTake, accent = color)
    }
}
