package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.SignerList
import com.ledgerlab.xrpl.wallet.SignerShare
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.WalletKeys
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xrpl.xrpl4j.model.transactions.Payment

/** A spend waiting for signatures. Every signer signs exactly [tx], so its sequence and expiry are fixed now. */
data class Proposal(val tx: Payment, val destination: String, val drops: Long, val memo: String, val shares: List<SignerShare>, val quorum: Int) {
    val ready: Boolean get() = shares.size >= quorum
}

data class TreasuryState(val treasury: String? = null, val balanceDrops: Long = 0, val signers: SignerList? = null)

class TreasuryViewModel(app: Application) : LabViewModel(app) {
    private val _state = MutableStateFlow(TreasuryState())
    val state: StateFlow<TreasuryState> = _state.asStateFlow()

    private val _proposal = MutableStateFlow<Proposal?>(null)
    val proposal: StateFlow<Proposal?> = _proposal.asStateFlow()

    private var treasuryId: String? = null

    fun treasury(): StoredWallet? = wallets.value.firstOrNull { it.id == treasuryId } ?: wallets.value.firstOrNull()

    fun chooseTreasury(id: String) {
        if (id == treasuryId) return
        treasuryId = id
        _proposal.value = null
        reload()
    }

    private fun appQueries() = AppQueries(rpc(), XrplRpc(network.value.clioRpcUrl))

    override suspend fun load() {
        val t = treasury() ?: return
        val info = rpc().accountInfo(t.address)
        _state.value = TreasuryState(t.address, info?.balanceDrops ?: 0, appQueries().signerList(t.address))
    }

    /** The treasury names its signers and how many must agree. Signed once, by the treasury's own key. */
    fun setSigners(signers: List<StoredWallet>, quorumText: String) {
        val label = "Set the signers"
        val t = treasury() ?: return fail(label, "Pick the treasury wallet.")
        val quorum = quorumText.toIntOrNull()?.takeIf { it in 1..signers.size } ?: return fail(label, "The quorum must be between 1 and ${signers.size}.")
        if (signers.size < 2) return fail(label, "Pick at least two signers.")
        _proposal.value = null
        runTx("$quorum of ${signers.size} signers for ${t.label}", wallet = t) { a, k, f ->
            LabTransactions.signerListSet(a, k, f, quorum, signers.map { it.address })
        }
    }

    /** Drafts a payment from the treasury. Nothing is signed yet. */
    fun propose(destination: String, amount: String, memo: String) {
        val label = "Propose a payment"
        val t = treasury() ?: return fail(label, "Pick the treasury wallet.")
        val list = _state.value.signers ?: return fail(label, "Set up the signers first.")
        if (!WalletKeys.isValidAddress(destination)) return fail(label, "Enter a valid destination.")
        val drops = Transactions.parseXrp(amount) ?: return fail(label, "Enter an XRP amount.")
        runWork(label) { step ->
            step("Reading the treasury's next sequence")
            val q = appQueries()
            val sequence = q.nextSequence(t.address) ?: throw IllegalStateException("The treasury isn't funded.")
            val (baseFee, ledger) = q.feeAndLedger()
            // About 20 minutes of ledgers for the signers to approve before the draft expires.
            val tx = LabTransactions.multiSigPayment(t.address, destination.trim(), drops, sequence, maxOf(baseFee, 10), list.quorum, ledger + 300, memo.ifBlank { null })
            _proposal.value = Proposal(tx, destination.trim(), drops, memo, emptyList(), list.quorum)
            "Drafted: ${Format.xrp(drops)} to ${shortAddr(destination.trim())}. It needs ${list.quorum} approvals."
        }
    }

    /** The selected wallet signs the draft as one of the treasury's signers. */
    fun approve() {
        val label = "Approve"
        val p = _proposal.value ?: return fail(label, "Nothing to approve.")
        val me = selected.value ?: return fail(label, "Pick a signer.")
        val list = _state.value.signers ?: return fail(label, "This treasury has no signers.")
        if (me.address !in list.signers) return fail(label, "${me.label} isn't one of the treasury's signers.")
        if (p.shares.any { it.address == me.address }) return fail(label, "${me.label} already approved this.")
        if (p.ready) return fail(label, "It already has enough approvals. Submit it.")
        runWork("${me.label} approves") { step ->
            step("Signing on this phone")
            val share = WalletKeys.multiSign(walletStore.secretOf(me), p.tx)
            _proposal.value = p.copy(shares = p.shares + share)
            "${me.label} signed (${p.shares.size + 1} of ${p.quorum}). Nothing has been sent yet."
        }
    }

    /** Combines the approvals into one transaction and submits it. Anyone can do this. */
    fun submit() {
        val label = "Send from the treasury"
        val p = _proposal.value?.takeIf { it.ready } ?: return fail(label, "It needs more approvals.")
        runWork(label) { step ->
            step("Combining ${p.shares.size} signatures")
            val signed = WalletKeys.assembleMultiSigned(p.tx, p.shares)
            step("Submitting")
            val rpc = rpc()
            val submitted = rpc.submit(signed.blob)
            if (!submitted.accepted) throw IllegalStateException("${submitted.engineResult}: ${submitted.message}")
            step("Waiting for validation")
            val final = rpc.waitForValidation(signed.hash, p.tx.lastLedgerSequence().get().toLong())
            if (!final.validated || final.result != "tesSUCCESS") throw IllegalStateException("The payment failed: ${final.result}")
            _proposal.value = null
            "Sent ${Format.xrp(p.drops)} with ${p.shares.size} signatures. Tx ${signed.hash.take(10)}…"
        }
    }

    fun discard() {
        _proposal.value = null
    }
}

@Composable
fun TreasuryLab(onBack: () -> Unit, vm: TreasuryViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val proposal by vm.proposal.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Gold

    LabScaffold("Shared treasury", "A club wallet that needs 2 of 3 to spend", accent, network, vm::selectNetwork, onBack, features = listOf("Multi-signing", "SignerListSet")) {
        item {
            UseCaseHero(
                brand = "ClubVault",
                tagline = "A shared wallet that needs a quorum to spend",
                icon = Icons.Outlined.AccountBalance,
                accent = accent,
                actors = listOf(Actor("Signers", Icons.Outlined.Groups), Actor("Treasury", Icons.Outlined.AccountBalance), Actor("Payee", Icons.Outlined.Person)),
                links = listOf("2 of 3 sign", "one payment"),
            )
        }
        if (wallets.size < 3) {
            item { NeedsWallet(accent, "A treasury and at least two signers: create and fund three wallets (four for 2 of 3) in the Wallet lab.") }
        } else {
            item { TreasuryPanel(vm, state, wallets, accent) }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            item { SignersPanel(vm, state, wallets, accent) }
            if (state.signers != null) {
                item { ProposalPanel(vm, proposal, state, wallet, wallets, accent) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "SignerListSet gives the treasury a list of signers and a quorum, here each with weight 1.",
                    "A spend is drafted once, with its Sequence, Fee and LastLedgerSequence fixed, and an empty SigningPubKey because no single key signs it.",
                    "Each signer signs the same draft on their own device. The signatures are combined into one Signers array and submitted together.",
                    "The fee is the base fee times (signers + 1), since every signature costs the network work to check.",
                    "Mockup notes: the treasury's own key still works here. A real club would disable the master key once the signer list is set, so only the quorum can move funds.",
                ),
                code = """
                    { "TransactionType": "Payment", "Account": "rTreasury…",
                      "SigningPubKey": "", "Fee": "36",
                      "Signers": [
                        { "Signer": { "Account": "rAlice…", "TxnSignature": "…" } },
                        { "Signer": { "Account": "rBob…",   "TxnSignature": "…" } }
                      ] }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/accounts/multi-signing",
            )
        }
    }
}

@Composable
private fun TreasuryPanel(vm: TreasuryViewModel, state: TreasuryState, wallets: List<StoredWallet>, accent: Color) {
    Panel(accent = accent, title = "Treasury") {
        WalletChips(wallets, vm.treasury(), accent, { vm.chooseTreasury(it.id) })
        Spacer(Modifier.height(8.dp))
        KeyValue("Balance", Format.xrp(state.balanceDrops))
        KeyValue("Spending rule", state.signers?.let { "${it.quorum} of ${it.signers.size} signers" } ?: "its own key only")
    }
}

@Composable
private fun SignersPanel(vm: TreasuryViewModel, state: TreasuryState, wallets: List<StoredWallet>, accent: Color) {
    val candidates = wallets.filter { it.address != state.treasury }
    var picked by rememberSaveable(state.treasury) { mutableStateOf(candidates.take(3).map { it.id }) }
    var quorum by rememberSaveable { mutableStateOf("2") }
    Panel(accent = accent, title = if (state.signers == null) "Choose signers" else "Signers") {
        state.signers?.signers?.forEach { KeyValue(nameOf(it, wallets), "weight 1") }
        if (state.signers != null) Spacer(Modifier.height(8.dp))
        Text(if (state.signers == null) "Who can approve spending" else "Change the signers", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEach { w ->
                LabChip(w.label, w.id in picked, accent) { picked = if (w.id in picked) picked - w.id else picked + w.id }
            }
        }
        Spacer(Modifier.height(8.dp))
        LabTextField(quorum, { quorum = it.filter(Char::isDigit).take(1) }, "Approvals needed", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(8.dp))
        LabButton("Set $quorum of ${picked.size}", { vm.setSigners(candidates.filter { it.id in picked }, quorum) }, Modifier.fillMaxWidth(), accent)
    }
}

@Composable
private fun ProposalPanel(vm: TreasuryViewModel, proposal: Proposal?, state: TreasuryState, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color) {
    val others = wallets.filter { it.address != state.treasury }
    var destination by rememberSaveable { mutableStateOf(others.firstOrNull()?.address ?: "") }
    var amount by rememberSaveable { mutableStateOf("5") }
    var memo by rememberSaveable { mutableStateOf("Hall rent") }
    Panel(accent = accent, title = if (proposal == null) "Propose a payment" else "Pending payment") {
        if (proposal == null) {
            Text("Pay to", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            WalletChips(others, others.firstOrNull { it.address == destination }, LabColors.Cyan, { destination = it.address })
            Spacer(Modifier.height(6.dp))
            LabTextField(destination, { destination = it.trim() }, "Destination", Modifier.fillMaxWidth(), accent, mono = true)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabTextField(amount, { amount = it }, "Amount (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                LabTextField(memo, { memo = it.take(60) }, "What for", Modifier.weight(1f), accent)
            }
            Spacer(Modifier.height(10.dp))
            LabButton("Draft it", { vm.propose(destination, amount, memo) }, Modifier.fillMaxWidth(), accent)
        } else {
            Text("${Format.xrp(proposal.drops)} to ${nameOf(proposal.destination, wallets)}", fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
            if (proposal.memo.isNotBlank()) Text(proposal.memo, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            Text("${proposal.shares.size} of ${proposal.quorum} approvals", color = if (proposal.ready) LabColors.Matrix else accent, fontWeight = FontWeight.SemiBold)
            state.signers?.signers?.forEach { s ->
                val share = proposal.shares.firstOrNull { it.address == s }
                Column {
                    KeyValue(nameOf(s, wallets), if (share != null) "signed" else "waiting", if (share != null) LabColors.Matrix else LabColors.TextMuted)
                    share?.let { Text(it.signature.take(28) + "…", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Approving as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            WalletChips(wallets.filter { it.address in state.signers?.signers.orEmpty() }, wallet, LabColors.Cyan, { vm.selectWallet(it.id) })
            Spacer(Modifier.height(8.dp))
            if (proposal.ready) {
                LabButton("Send it", vm::submit, Modifier.fillMaxWidth(), LabColors.Matrix)
            } else {
                LabButton("Approve as ${wallet?.label ?: "signer"}", vm::approve, Modifier.fillMaxWidth(), accent)
            }
            Spacer(Modifier.height(6.dp))
            LabOutlineButton("Discard draft", vm::discard, Modifier.fillMaxWidth(), LabColors.Danger)
        }
    }
}
