package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.LedgerCheck
import com.ledgerlab.xrpl.labs.LedgerText
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount

data class Plan(val name: String, val drops: Long) {
    /** Every check for this plan carries the same InvoiceID, so both sides can tell what it pays for. */
    val invoiceId: String get() = LedgerText.idFor("ledgerlab-sub:$name")
}

val PLANS = listOf(Plan("Streaming", 2_000_000), Plan("News", 1_000_000), Plan("Gym", 5_000_000))

fun planFor(check: LedgerCheck): Plan? = PLANS.firstOrNull { it.invoiceId.equals(check.invoiceId, ignoreCase = true) }

class SubscriptionsViewModel(app: Application) : LabViewModel(app) {
    private val _checks = MutableStateFlow<List<LedgerCheck>>(emptyList())
    /** Checks the selected wallet wrote or can cash. */
    val checks: StateFlow<List<LedgerCheck>> = _checks.asStateFlow()

    override suspend fun load() {
        val wallet = selected.value ?: return
        _checks.value = AppQueries(rpc(), XrplRpc(network.value.clioRpcUrl)).checks(wallet.address).sortedBy { it.expiration ?: Long.MAX_VALUE }
    }

    /** The customer writes this period's check. A real wallet app would do this on schedule. */
    fun pay(plan: Plan, merchant: StoredWallet, periodMinutes: String) {
        val label = "Pay for ${plan.name}"
        val customer = selected.value ?: return fail(label, "Pick the customer's wallet.")
        if (customer.id == merchant.id) return fail(label, "Pick a different wallet as the merchant.")
        val minutes = periodMinutes.toLongOrNull()?.takeIf { it in 1..44_640 } ?: return fail(label, "The billing period is in minutes (a month is 43,200).")
        val expires = rippleNow() + minutes * 60
        runTx("${plan.name}: ${Format.xrp(plan.drops)} check to ${merchant.label}") { a, k, f ->
            LabTransactions.checkCreate(a, k, f, merchant.address, XrpCurrencyAmount.ofDrops(plan.drops), expires, plan.invoiceId)
        }
    }

    /** The merchant takes the money. Only the destination can cash a check. */
    fun collect(check: LedgerCheck) {
        val drops = (check.sendMax as? Amount.Xrp)?.drops ?: return fail("Collect", "This demo only cashes XRP checks.")
        runTx("Collect ${Format.xrp(drops)}") { a, k, f -> LabTransactions.checkCash(a, k, f, check.id, XrpCurrencyAmount.ofDrops(drops)) }
    }

    /** The customer cancels before it's cashed, or anyone clears it once it has expired. */
    fun cancel(check: LedgerCheck) = runTx("Cancel check") { a, k, f -> LabTransactions.checkCancel(a, k, f, check.id) }
}

@Composable
fun SubscriptionsLab(onBack: () -> Unit, vm: SubscriptionsViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Cyan
    var now by remember { mutableLongStateOf(rippleNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = rippleNow()
        }
    }
    val others = wallets.filter { it.id != wallet?.id }
    var merchantId by rememberSaveable(wallet?.id) { mutableStateOf(others.firstOrNull()?.id) }
    var planName by rememberSaveable { mutableStateOf(PLANS.first().name) }
    var period by rememberSaveable { mutableStateOf("5") }
    val incoming = checks.filter { it.to == wallet?.address }
    val outgoing = checks.filter { it.from == wallet?.address }

    LabScaffold("Subscriptions", "Pay by Check: the merchant pulls, you stay in control", accent, network, vm::selectNetwork, onBack) {
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "A subscription has a customer and a merchant. Create and fund two wallets in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent, title = "Acting as") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            item {
                Panel(accent = accent, title = "Subscribe as ${wallet?.label ?: "customer"}") {
                    Text("Merchant", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(others, others.firstOrNull { it.id == merchantId }, LabColors.Gold, { merchantId = it.id })
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PLANS.forEach { p -> LabChip("${p.name} ${Format.xrp(p.drops)}", planName == p.name, accent) { planName = p.name } }
                    }
                    Spacer(Modifier.height(8.dp))
                    LabTextField(period, { period = it.filter(Char::isDigit) }, "Billing period (minutes)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(10.dp))
                    val plan = PLANS.first { it.name == planName }
                    LabButton(
                        "Pay this period",
                        { others.firstOrNull { it.id == merchantId }?.let { vm.pay(plan, it, period) } },
                        Modifier.fillMaxWidth(),
                        accent,
                        enabled = merchantId != null,
                    )
                    Text("Writes a Check the merchant can cash until the period ends. Nothing leaves your account until they do.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }
            }
            item {
                CheckList("Your outstanding checks (${outgoing.size})", outgoing, wallets, now, emptyText = "None. Each period's check shows here until the merchant cashes it.") { c, expired ->
                    LabOutlineButton(if (expired) "Clear" else "Cancel", { vm.cancel(c) }, accent = LabColors.Danger)
                }
            }
            item {
                CheckList("To collect as merchant (${incoming.size})", incoming, wallets, now, emptyText = "No checks waiting for ${wallet?.label ?: "you"}.") { c, expired ->
                    if (expired) LabOutlineButton("Clear", { vm.cancel(c) }, accent = LabColors.TextMuted)
                    else LabButton("Collect", { vm.collect(c) }, accent = LabColors.Matrix)
                }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "A Check is a promise on the ledger: the merchant may pull up to SendMax from the customer's account before it expires.",
                    "The customer writes one check per billing period. The money stays in their account, so they can cancel any time before it's cashed, and the merchant never holds a card number.",
                    "The merchant cashes it with CheckCash. If the customer's balance is short, cashing fails and the subscription lapses.",
                    "Every check for a plan carries the same InvoiceID, so both sides can match payments to plans without a database.",
                    "Mockup notes: the ledger has no recurring pull payments, so a real wallet would write each check on schedule and let the customer cap or stop it.",
                ),
                code = """
                    { "TransactionType": "CheckCreate", "Account": "rCustomer…",
                      "Destination": "rMerchant…", "SendMax": "2000000",
                      "Expiration": <end of period>, "InvoiceID": sha256("ledgerlab-sub:Streaming") }
                    { "TransactionType": "CheckCash", "Account": "rMerchant…",
                      "CheckID": "49647F0D…", "Amount": "2000000" }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/payment-types/checks",
            )
        }
    }
}

@Composable
private fun CheckList(
    title: String,
    checks: List<LedgerCheck>,
    wallets: List<StoredWallet>,
    now: Long,
    emptyText: String,
    action: @Composable (LedgerCheck, Boolean) -> Unit,
) {
    Panel(accent = LabColors.Cyan, title = title) {
        if (checks.isEmpty()) Text(emptyText, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            checks.forEach { c ->
                val left = c.expiration?.let { timeLeft(it, now) }
                val expired = c.expiration != null && left == null
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${planFor(c)?.name ?: "Check"} · ${Format.amount(c.sendMax)}", fontWeight = FontWeight.SemiBold, color = LabColors.TextPrimary)
                        Text(
                            "${nameOf(c.from, wallets)} → ${nameOf(c.to, wallets)} · " + when {
                                expired -> "expired"
                                left != null -> "cash within $left"
                                else -> "no expiry"
                            },
                            fontFamily = Mono,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (expired) LabColors.Danger else LabColors.TextSecondary,
                        )
                    }
                    action(c, expired)
                }
            }
        }
    }
}
