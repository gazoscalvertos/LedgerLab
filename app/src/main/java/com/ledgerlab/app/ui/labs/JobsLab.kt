package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Work
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
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.EscrowLocks
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount

enum class JobStage(val label: String) {
    OPEN("In progress"),
    APPROVED("Approved: ready to claim"),
    PAID("Paid"),
    REFUNDED("Refunded"),
}

/**
 * A job as the app remembers it. The escrow is on the ledger; the fulfillment (the secret that releases
 * it) is kept encrypted on this phone, standing in for the client's own device.
 */
data class Job(
    val client: String,
    val freelancer: String,
    val title: String,
    val drops: Long,
    val sequence: Long,
    val deadline: Long,
    val conditionHex: String,
    val fulfillmentHex: String,
    val stage: JobStage,
    /** False once the escrow is gone from the ledger. */
    val live: Boolean = true,
)

class JobsViewModel(app: Application) : LabViewModel(app) {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private fun storeKey() = "jobs-${network.value.name}"

    private fun saved(): List<Job> = settingsStore.secret(storeKey())?.let(::decode).orEmpty()

    private fun save(jobs: List<Job>) = settingsStore.setSecret(storeKey(), encode(jobs))

    private fun update(job: Job, change: (Job) -> Job) {
        val all = saved().map { if (it.client == job.client && it.sequence == job.sequence) change(it) else it }
        save(all)
        _jobs.value = _jobs.value.map { if (it.client == job.client && it.sequence == job.sequence) change(it) else it }
    }

    override suspend fun load() {
        val q = queries()
        val jobs = saved()
        val liveConditions = jobs.map { it.client }.distinct().flatMap { client ->
            runCatching { q.escrows(client) }.getOrDefault(emptyList()).mapNotNull { it.condition?.uppercase() }
        }.toSet()
        _jobs.value = jobs.map { it.copy(live = it.conditionHex.uppercase() in liveConditions) }.sortedByDescending { it.sequence }
    }

    /** The client locks the budget in an escrow only the job's secret can release, refundable after the deadline. */
    fun post(title: String, freelancer: StoredWallet, budget: String, minutes: String) {
        val label = "Post a job"
        val client = selected.value ?: return fail(label, "Pick the client's wallet.")
        if (client.id == freelancer.id) return fail(label, "Pick a different wallet as the freelancer.")
        val drops = Transactions.parseXrp(budget) ?: return fail(label, "Enter a budget in XRP.")
        val mins = minutes.toLongOrNull()?.takeIf { it in 1..43_200 } ?: return fail(label, "The deadline is in minutes, up to 30 days.")
        val name = title.trim().ifBlank { "Untitled job" }.take(80)
        runWork("Post \"$name\"") { step ->
            step("Creating the lock")
            val lock = EscrowLocks.newLock()
            val deadline = rippleNow() + mins * 60
            val created = submitAs(client, "Locking ${Format.xrp(drops)} in escrow", step) { a, k, f ->
                LabTransactions.escrowCreateConditional(a, k, f, freelancer.address, XrpCurrencyAmount.ofDrops(drops), lock.conditionHex, deadline, memo = "job: $name")
            }
            val sequence = AppQueries(rpc(), XrplRpc(network.value.clioRpcUrl)).sequenceOf(created.hash!!)
                ?: throw IllegalStateException("Escrow created, but its sequence couldn't be read back.")
            save(saved() + Job(client.address, freelancer.address, name, drops, sequence, deadline, lock.conditionHex, lock.fulfillmentHex, JobStage.OPEN))
            "${Format.xrp(drops)} is locked for ${freelancer.label}. Nobody can take it without the secret, and you can reclaim it after ${formatLedgerTime(deadline)}."
        }
    }

    /** The client accepts the work: in a real app this sends the fulfillment to the freelancer. */
    fun approve(job: Job) {
        if (selected.value?.address != job.client) return fail("Approve", "Only the client can approve the work.")
        update(job) { it.copy(stage = JobStage.APPROVED) }
        runWork("Approve \"${job.title}\"") { "Approved. The secret is now with the freelancer, who can claim the payment." }
    }

    /** The freelancer reveals the fulfillment in an EscrowFinish and is paid. */
    fun claim(job: Job) {
        val label = "Claim payment"
        val freelancer = wallets.value.firstOrNull { it.address == job.freelancer } ?: return fail(label, "The freelancer's wallet isn't on this phone.")
        if (job.stage != JobStage.APPROVED) return fail(label, "The client hasn't approved the work yet, so the freelancer doesn't have the secret.")
        runWork("Claim \"${job.title}\"") { step ->
            submitAs(freelancer, "Releasing the escrow", step) { a, k, f ->
                LabTransactions.escrowFinishWithFulfillment(a, k, f, job.client, job.sequence, job.conditionHex, job.fulfillmentHex)
            }
            update(job) { it.copy(stage = JobStage.PAID, live = false) }
            "${freelancer.label} received ${Format.xrp(job.drops)}."
        }
    }

    /** Without the secret, finishing fails: shows the ledger checking the condition. */
    fun claimWithoutSecret(job: Job) {
        val freelancer = wallets.value.firstOrNull { it.address == job.freelancer } ?: return fail("Claim", "The freelancer's wallet isn't on this phone.")
        val forged = EscrowLocks.newLock()
        runTx("Claim with a guessed secret", wallet = freelancer) { a, k, f ->
            LabTransactions.escrowFinishWithFulfillment(a, k, f, job.client, job.sequence, job.conditionHex, forged.fulfillmentHex)
        }
    }

    /** After the deadline the client takes the money back. */
    fun refund(job: Job) {
        val label = "Refund"
        val client = wallets.value.firstOrNull { it.address == job.client } ?: return fail(label, "The client's wallet isn't on this phone.")
        if (rippleNow() <= job.deadline) return fail(label, "The deadline hasn't passed yet.")
        runWork("Refund \"${job.title}\"") { step ->
            submitAs(client, "Cancelling the escrow", step) { a, k, f -> LabTransactions.escrowCancel(a, k, f, job.client, job.sequence) }
            update(job) { it.copy(stage = JobStage.REFUNDED, live = false) }
            "${Format.xrp(job.drops)} returned to ${client.label}."
        }
    }

    fun forget(job: Job) {
        save(saved().filterNot { it.client == job.client && it.sequence == job.sequence })
        reload()
    }

    private companion object {
        fun encode(jobs: List<Job>): String = buildJsonArray {
            jobs.forEach { j ->
                add(buildJsonObject {
                    put("client", j.client)
                    put("freelancer", j.freelancer)
                    put("title", j.title)
                    put("drops", j.drops)
                    put("sequence", j.sequence)
                    put("deadline", j.deadline)
                    put("condition", j.conditionHex)
                    put("fulfillment", j.fulfillmentHex)
                    put("stage", j.stage.name)
                })
            }
        }.toString()

        fun decode(text: String): List<Job> = runCatching {
            (Json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.content
                Job(
                    client = s("client") ?: return@mapNotNull null,
                    freelancer = s("freelancer") ?: return@mapNotNull null,
                    title = s("title").orEmpty(),
                    drops = s("drops")?.toLongOrNull() ?: 0,
                    sequence = s("sequence")?.toLongOrNull() ?: return@mapNotNull null,
                    deadline = s("deadline")?.toLongOrNull() ?: 0,
                    conditionHex = s("condition") ?: return@mapNotNull null,
                    fulfillmentHex = s("fulfillment") ?: return@mapNotNull null,
                    stage = runCatching { JobStage.valueOf(o["stage"]!!.jsonPrimitive.content) }.getOrDefault(JobStage.OPEN),
                )
            }
        }.getOrDefault(emptyList())
    }
}

@Composable
fun JobsLab(onBack: () -> Unit, vm: JobsViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Orange
    var now by remember { mutableLongStateOf(rippleNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = rippleNow()
        }
    }

    LabScaffold("Freelance jobs", "Escrowed pay, released when the work is accepted", accent, network, vm::selectNetwork, onBack, features = listOf("Escrow", "Crypto-conditions", "CancelAfter")) {
        item {
            UseCaseHero(
                brand = "GigLock",
                tagline = "Get paid for finished work, guaranteed",
                icon = Icons.Outlined.Work,
                accent = accent,
                actors = listOf(Actor("Client", Icons.Outlined.Person), Actor("Escrow", Icons.Outlined.Lock), Actor("Freelancer", Icons.Outlined.Brush)),
                links = listOf("locks the budget", "secret releases it"),
            )
        }
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "A job has a client and a freelancer. Create and fund two wallets in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent, title = "Acting as") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            item { PostJobPanel(vm, wallets, wallet, accent) }
            item {
                Panel(accent = accent, title = "Jobs (${jobs.size})") {
                    if (jobs.isEmpty()) Text("No jobs yet. Post one above as the client.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        jobs.forEach { JobRow(vm, it, wallet, wallets, now) }
                    }
                    Spacer(Modifier.height(8.dp))
                    LabOutlineButton("Refresh", vm::reload, Modifier.fillMaxWidth(), accent)
                }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "The client posts a job by locking the budget in an escrow to the freelancer. It carries a crypto-condition: the SHA-256 of a secret only the client knows.",
                    "The freelancer can see the money is really there before starting work, and the client can't quietly take it back before the deadline.",
                    "When the client accepts the work they hand over the secret (the fulfillment). The freelancer submits EscrowFinish with it and the ledger checks it against the condition.",
                    "A wrong secret fails with tecCRYPTOCONDITION_ERROR. Try it with \"Claim with a guessed secret\".",
                    "If the deadline (CancelAfter) passes without approval, the client cancels the escrow and gets the money back.",
                    "Mockup notes: here one phone holds both wallets and the secret. A real app would send the fulfillment over a secure channel, and might add an arbiter with multi-signing for disputes.",
                ),
                code = """
                    {
                      "TransactionType": "EscrowCreate",
                      "Account": "rClient…",
                      "Destination": "rFreelancer…",
                      "Amount": "25000000",
                      "Condition": "A0258020…810120",   // PREIMAGE-SHA-256
                      "CancelAfter": <deadline>
                    }
                    { "TransactionType": "EscrowFinish", "Fulfillment": "A0228020…", … }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/payment-types/escrow · crypto-conditions",
            )
        }
    }
}

@Composable
private fun PostJobPanel(vm: JobsViewModel, wallets: List<StoredWallet>, wallet: StoredWallet?, accent: Color) {
    val others = wallets.filter { it.id != wallet?.id }
    var freelancerId by rememberSaveable(wallet?.id) { mutableStateOf(others.firstOrNull()?.id) }
    var title by rememberSaveable { mutableStateOf("Logo design") }
    var budget by rememberSaveable { mutableStateOf("25") }
    var minutes by rememberSaveable { mutableStateOf("10") }
    Panel(accent = accent, title = "Post a job as ${wallet?.label ?: "client"}") {
        LabTextField(title, { title = it.take(80) }, "Job", Modifier.fillMaxWidth(), accent)
        Spacer(Modifier.height(8.dp))
        Text("Freelancer", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        WalletChips(others, others.firstOrNull { it.id == freelancerId }, LabColors.Gold, { freelancerId = it.id })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabTextField(budget, { budget = it }, "Budget (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
            LabTextField(minutes, { minutes = it.filter(Char::isDigit) }, "Deadline (min)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
        }
        Spacer(Modifier.height(10.dp))
        LabButton(
            "Lock budget in escrow",
            { others.firstOrNull { it.id == freelancerId }?.let { vm.post(title, it, budget, minutes) } },
            Modifier.fillMaxWidth(),
            accent,
            enabled = freelancerId != null,
        )
    }
}

@Composable
private fun JobRow(vm: JobsViewModel, job: Job, wallet: StoredWallet?, wallets: List<StoredWallet>, now: Long) {
    val isClient = wallet?.address == job.client
    val isFreelancer = wallet?.address == job.freelancer
    val left = timeLeft(job.deadline, now)
    val active = job.live && (job.stage == JobStage.OPEN || job.stage == JobStage.APPROVED)
    val stageColor = when (job.stage) {
        JobStage.OPEN -> LabColors.Cyan
        JobStage.APPROVED -> LabColors.Gold
        JobStage.PAID -> LabColors.Matrix
        JobStage.REFUNDED -> LabColors.TextMuted
    }
    Column {
        Text(job.title, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
        Text(
            "${Format.xrp(job.drops)} · ${nameOf(job.client, wallets)} → ${nameOf(job.freelancer, wallets)}",
            fontFamily = Mono,
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
        )
        Text(
            when {
                !job.live && active -> "Escrow no longer on the ledger"
                active -> job.stage.label + (left?.let { " · deadline in $it" } ?: " · deadline passed")
                else -> job.stage.label
            },
            style = MaterialTheme.typography.bodySmall,
            color = stageColor,
        )
        if (job.stage == JobStage.APPROVED && isFreelancer) {
            Text("Secret received: ${job.fulfillmentHex.take(24)}…", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
        if (active && job.live) {
            Spacer(Modifier.height(8.dp))
            when {
                isClient && job.stage == JobStage.OPEN && left != null ->
                    LabButton("Approve the work", { vm.approve(job) }, Modifier.fillMaxWidth(), LabColors.Matrix)
                isClient && left == null ->
                    LabButton("Refund to me", { vm.refund(job) }, Modifier.fillMaxWidth(), LabColors.Danger)
                isClient ->
                    Text("Waiting for the freelancer to claim.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                isFreelancer && job.stage == JobStage.APPROVED ->
                    LabButton("Claim ${Format.xrp(job.drops)}", { vm.claim(job) }, Modifier.fillMaxWidth(), LabColors.Matrix)
                isFreelancer ->
                    LabOutlineButton("Claim with a guessed secret", { vm.claimWithoutSecret(job) }, Modifier.fillMaxWidth(), LabColors.Danger)
                else -> Text("Switch to the client or freelancer to act.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            }
        } else if (!active || !job.live) {
            Spacer(Modifier.height(4.dp))
            Text("Remove from list", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted, modifier = Modifier.clickable { vm.forget(job) })
        }
    }
}
