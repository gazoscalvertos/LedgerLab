package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.ledgerlab.app.ui.components.UseCaseHero
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.EscrowEntry
import com.ledgerlab.xrpl.labs.EscrowLocks
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.wallet.Transactions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount

/**
 * A campaign. Pledges are escrows to [creator] locked by [conditionHex]; the fulfillment, held by the
 * app as the campaign's referee, is only used once pledges reach [goalDrops].
 */
data class Campaign(
    val title: String,
    val creator: String,
    val goalDrops: Long,
    val deadline: Long,
    val conditionHex: String,
    val fulfillmentHex: String,
)

data class Pledge(val escrow: EscrowEntry) {
    val drops: Long get() = (escrow.amount as? Amount.Xrp)?.drops ?: 0L
}

data class CampaignView(val campaign: Campaign, val pledges: List<Pledge>) {
    val raised: Long get() = pledges.sumOf { it.drops }
    val funded: Boolean get() = raised >= campaign.goalDrops
}

class CrowdfundViewModel(app: Application) : LabViewModel(app) {
    private val _campaigns = MutableStateFlow<List<CampaignView>>(emptyList())
    val campaigns: StateFlow<List<CampaignView>> = _campaigns.asStateFlow()

    private fun storeKey() = "campaigns-${network.value.name}"

    private fun saved(): List<Campaign> = settingsStore.secret(storeKey())?.let(::decode).orEmpty()

    override suspend fun load() {
        val q = queries()
        _campaigns.value = saved().map { c ->
            val escrows = runCatching { q.escrows(c.creator) }.getOrDefault(emptyList())
            CampaignView(c, escrows.filter { it.destination == c.creator && it.condition.equals(c.conditionHex, ignoreCase = true) }.map(::Pledge))
        }.reversed()
    }

    /** Starting a campaign is free: it's a goal, a deadline and a fresh lock. Pledges are what hit the ledger. */
    fun create(title: String, goal: String, minutes: String) {
        val label = "Start a campaign"
        val creator = selected.value ?: return fail(label, "Pick the creator's wallet.")
        val goalDrops = Transactions.parseXrp(goal) ?: return fail(label, "Enter a goal in XRP.")
        val mins = minutes.toLongOrNull()?.takeIf { it in 2..43_200 } ?: return fail(label, "The deadline is in minutes (2 or more).")
        val lock = EscrowLocks.newLock()
        val campaign = Campaign(title.trim().ifBlank { "My project" }.take(60), creator.address, goalDrops, rippleNow() + mins * 60, lock.conditionHex, lock.fulfillmentHex)
        settingsStore.setSecret(storeKey(), encode(saved() + campaign))
        runWork(label) { "\"${campaign.title}\" is live: ${Format.xrp(goalDrops)} by ${formatLedgerTime(campaign.deadline)}." }
    }

    /** A backer locks their pledge in an escrow to the creator. It can't be claimed without the campaign's secret. */
    fun pledge(view: CampaignView, amount: String) {
        val c = view.campaign
        val label = "Pledge"
        val backer = selected.value ?: return fail(label, "Pick a backer's wallet.")
        if (backer.address == c.creator) return fail(label, "Switch to a backer: creators can't back their own campaign here.")
        if (rippleNow() >= c.deadline) return fail(label, "This campaign has ended.")
        val drops = Transactions.parseXrp(amount) ?: return fail(label, "Enter an XRP amount.")
        runTx("Pledge ${Format.xrp(drops)} to \"${c.title}\"") { a, k, f ->
            LabTransactions.escrowCreateConditional(a, k, f, c.creator, XrpCurrencyAmount.ofDrops(drops), c.conditionHex, c.deadline, memo = "pledge: ${c.title}")
        }
    }

    /** Goal reached: the creator collects every pledge with the fulfillment, one EscrowFinish each. */
    fun collect(view: CampaignView) {
        val c = view.campaign
        val label = "Collect pledges"
        val creator = wallets.value.firstOrNull { it.address == c.creator } ?: return fail(label, "The creator's wallet isn't on this phone.")
        if (!view.funded) return fail(label, "The goal isn't met, so the referee won't release the secret.")
        if (rippleNow() >= c.deadline) return fail(label, "The deadline passed, so pledges can only be refunded.")
        runWork("Collect \"${c.title}\"") { step ->
            view.pledges.forEachIndexed { i, p ->
                val seq = p.escrow.createSequence ?: throw IllegalStateException("Couldn't find pledge ${i + 1}'s sequence.")
                submitAs(creator, "Pledge ${i + 1} of ${view.pledges.size}", step) { a, k, f ->
                    LabTransactions.escrowFinishWithFulfillment(a, k, f, p.escrow.owner, seq, c.conditionHex, c.fulfillmentHex)
                }
            }
            "${creator.label} collected ${Format.xrp(view.raised)} from ${view.pledges.size} backers."
        }
    }

    /** After the deadline anyone may cancel an expired escrow, so one tap refunds every backer. */
    fun refundAll(view: CampaignView) {
        val c = view.campaign
        val label = "Refund backers"
        val signer = selected.value ?: return fail(label, "Pick any funded wallet to send the refunds.")
        if (rippleNow() <= c.deadline) return fail(label, "Refunds open when the deadline passes.")
        runWork("Refund \"${c.title}\"") { step ->
            view.pledges.forEachIndexed { i, p ->
                val seq = p.escrow.createSequence ?: throw IllegalStateException("Couldn't find pledge ${i + 1}'s sequence.")
                submitAs(signer, "Refund ${i + 1} of ${view.pledges.size}", step) { a, k, f -> LabTransactions.escrowCancel(a, k, f, p.escrow.owner, seq) }
            }
            "${view.pledges.size} backers got ${Format.xrp(view.raised)} back. ${signer.label} paid the fees."
        }
    }

    fun forget(view: CampaignView) {
        settingsStore.setSecret(storeKey(), encode(saved().filterNot { it.conditionHex == view.campaign.conditionHex }))
        reload()
    }

    private companion object {
        fun encode(list: List<Campaign>): String = buildJsonArray {
            list.forEach { c ->
                add(buildJsonObject {
                    put("title", c.title)
                    put("creator", c.creator)
                    put("goal", c.goalDrops)
                    put("deadline", c.deadline)
                    put("condition", c.conditionHex)
                    put("fulfillment", c.fulfillmentHex)
                })
            }
        }.toString()

        fun decode(text: String): List<Campaign> = runCatching {
            (Json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Campaign(
                    o.text("title").orEmpty(),
                    o.text("creator") ?: return@mapNotNull null,
                    o.text("goal")?.toLongOrNull() ?: return@mapNotNull null,
                    o.text("deadline")?.toLongOrNull() ?: return@mapNotNull null,
                    o.text("condition") ?: return@mapNotNull null,
                    o.text("fulfillment") ?: return@mapNotNull null,
                )
            }
        }.getOrDefault(emptyList())
    }
}

@Composable
fun CrowdfundLab(onBack: () -> Unit, vm: CrowdfundViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val campaigns by vm.campaigns.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Matrix
    var now by remember { mutableLongStateOf(rippleNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = rippleNow()
        }
    }

    LabScaffold("Crowdfunding", "All or nothing: pledges unlock only if the goal is met", accent, network, vm::selectNetwork, onBack, features = listOf("Escrow", "Crypto-conditions", "EscrowCancel")) {
        item {
            UseCaseHero(
                brand = "BackIt",
                tagline = "All-or-nothing crowdfunding",
                icon = Icons.Outlined.VolunteerActivism,
                accent = accent,
                actors = listOf(Actor("Backers", Icons.Outlined.Groups), Actor("Escrow", Icons.Outlined.Lock), Actor("Creator", Icons.Outlined.Person)),
                links = listOf("pledge", "released if the goal is met"),
            )
        }
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "A campaign needs a creator and at least one backer. Create and fund two or three wallets in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent, title = "Acting as") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            item { NewCampaignPanel(vm, wallet, accent) }
            campaigns.forEach { view -> item { CampaignPanel(vm, view, wallet, wallets, now, accent) } }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "Each pledge is an escrow from the backer to the creator, locked by the campaign's crypto-condition, with CancelAfter set to the deadline.",
                    "The money stays in the backer's escrow, visible on the ledger, so everyone can check the total raised.",
                    "If the total reaches the goal before the deadline, the referee releases the fulfillment and the creator finishes every escrow.",
                    "If not, the escrows expire and anyone can cancel them, sending each pledge straight back to its backer.",
                    "Mockup notes: the ledger can't sum escrows itself, so a referee (here the app) holds the secret and checks the goal. A real platform would run that as an audited service.",
                ),
                code = """
                    // each pledge
                    { "TransactionType": "EscrowCreate", "Account": "rBacker…",
                      "Destination": "rCreator…", "Amount": "10000000",
                      "Condition": "A025…", "CancelAfter": <deadline> }

                    // goal met: one per pledge
                    { "TransactionType": "EscrowFinish", "Owner": "rBacker…",
                      "OfferSequence": 12, "Fulfillment": "A022…" }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/payment-types/escrow",
            )
        }
    }
}

@Composable
private fun NewCampaignPanel(vm: CrowdfundViewModel, wallet: StoredWallet?, accent: Color) {
    var title by rememberSaveable { mutableStateOf("Solar kit for the club") }
    var goal by rememberSaveable { mutableStateOf("30") }
    var minutes by rememberSaveable { mutableStateOf("15") }
    Panel(accent = accent, title = "Start a campaign as ${wallet?.label ?: "creator"}") {
        LabTextField(title, { title = it.take(60) }, "Project", Modifier.fillMaxWidth(), accent)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabTextField(goal, { goal = it }, "Goal (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
            LabTextField(minutes, { minutes = it.filter(Char::isDigit) }, "Runs for (min)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
        }
        Spacer(Modifier.height(10.dp))
        LabButton("Launch", { vm.create(title, goal, minutes) }, Modifier.fillMaxWidth(), accent)
    }
}

@Composable
private fun CampaignPanel(vm: CrowdfundViewModel, view: CampaignView, wallet: StoredWallet?, wallets: List<StoredWallet>, now: Long, accent: Color) {
    val c = view.campaign
    val left = timeLeft(c.deadline, now)
    val isCreator = wallet?.address == c.creator
    var amount by rememberSaveable(c.conditionHex) { mutableStateOf("10") }
    Panel(accent = if (view.funded) LabColors.Gold else accent, title = c.title) {
        Text("by ${nameOf(c.creator, wallets)}", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Text("${Format.xrp(view.raised)} of ${Format.xrp(c.goalDrops)}", fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
        LinearProgressIndicator(
            progress = { (view.raised.toFloat() / c.goalDrops.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (view.funded) LabColors.Gold else accent,
            trackColor = LabColors.Outline,
        )
        Text(
            "${view.pledges.size} backers · " + (left?.let { "ends in $it" } ?: "ended") + if (view.funded) " · goal met" else "",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextMuted,
        )
        view.pledges.forEach { p -> KeyValue(nameOf(p.escrow.owner, wallets), Format.xrp(p.drops)) }
        Spacer(Modifier.height(10.dp))
        when {
            left != null && !isCreator -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabTextField(amount, { amount = it }, "Pledge (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                LabButton("Back it", { vm.pledge(view, amount) }, Modifier.weight(1f), accent)
            }
            left != null && view.funded && view.pledges.isNotEmpty() ->
                LabButton("Collect ${Format.xrp(view.raised)}", { vm.collect(view) }, Modifier.fillMaxWidth(), LabColors.Gold)
            left != null ->
                Text("Pledges stay locked until the goal is met.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            view.pledges.isNotEmpty() ->
                LabButton("Refund all ${view.pledges.size} backers", { vm.refundAll(view) }, Modifier.fillMaxWidth(), LabColors.Danger)
            else -> LabOutlineButton("Remove campaign", { vm.forget(view) }, Modifier.fillMaxWidth(), LabColors.TextMuted)
        }
    }
}
