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
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.EscrowEntry
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.WalletKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Seconds between the Unix epoch and the Ripple epoch (2000-01-01). */
private const val RIPPLE_EPOCH_OFFSET = 946_684_800L

fun rippleNow(): Long = System.currentTimeMillis() / 1000 - RIPPLE_EPOCH_OFFSET

class EscrowViewModel(app: Application) : LabViewModel(app) {
    private val _escrows = MutableStateFlow<List<EscrowEntry>>(emptyList())
    val escrows: StateFlow<List<EscrowEntry>> = _escrows.asStateFlow()

    override suspend fun load() {
        val wallet = selected.value ?: return
        _escrows.value = queries().escrows(wallet.address)
    }

    fun create(destination: String, amount: String, releaseMinutes: String, cancelMinutes: String) {
        val label = "Lock XRP in escrow"
        if (!WalletKeys.isValidAddress(destination)) return fail(label, "Enter a valid destination address.")
        val drops = Transactions.parseXrp(amount) ?: return fail(label, "Enter an XRP amount.")
        val release = releaseMinutes.toLongOrNull()?.takeIf { it in 1..525_600 } ?: return fail(label, "Release after 1 or more minutes.")
        val cancel = if (cancelMinutes.isBlank()) null else cancelMinutes.toLongOrNull()?.takeIf { it > release }
            ?: return fail(label, "Cancel time must be later than the release time.")
        val now = rippleNow()
        runTx(label) { a, k, f ->
            LabTransactions.escrowCreate(a, k, f, destination.trim(), drops, now + release * 60, cancel?.let { now + it * 60 })
        }
    }

    fun finish(escrow: EscrowEntry) {
        val seq = escrow.createSequence ?: return fail("Release escrow", "Couldn't find the EscrowCreate sequence for this escrow.")
        runTx("Release ${Format.xrp(escrow.drops)}") { a, k, f -> LabTransactions.escrowFinish(a, k, f, escrow.owner, seq) }
    }
}

@Composable
fun EscrowLab(onBack: () -> Unit, vm: EscrowViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val escrows by vm.escrows.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Orange

    val otherWallet = wallets.firstOrNull { it.id != wallet?.id }
    var destination by rememberSaveable(otherWallet?.address) { mutableStateOf(otherWallet?.address ?: "") }
    var amount by rememberSaveable { mutableStateOf("10") }
    var release by rememberSaveable { mutableStateOf("1") }
    var cancel by rememberSaveable { mutableStateOf("") }

    var now by remember { mutableLongStateOf(rippleNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = rippleNow()
        }
    }

    LabScaffold("Escrow", "Time-locked XRP", accent, network, vm::selectNetwork, onBack) {
        if (wallets.isEmpty()) {
            item { NeedsWallet(accent) }
        } else {
            item {
                Panel(accent = accent, title = "Lock XRP") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(12.dp))
                    LabTextField(destination, { destination = it }, "Destination", Modifier.fillMaxWidth(), accent, mono = true)
                    Spacer(Modifier.height(8.dp))
                    LabTextField(amount, { amount = it }, "Amount (XRP)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabTextField(release, { release = it }, "Release after (min)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
                        LabTextField(cancel, { cancel = it }, "Cancel after (min)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
                    }
                    Spacer(Modifier.height(10.dp))
                    LabButton("Create escrow", { vm.create(destination, amount, release, cancel) }, Modifier.fillMaxWidth(), accent)
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item {
                Panel(accent = accent, title = "Escrows involving this wallet") {
                    ErrorLine(error)
                    if (escrows.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    escrows.forEach { e -> EscrowRow(e, now) { vm.finish(e) } }
                }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "EscrowCreate moves XRP out of your balance into an Escrow object on the ledger.",
                    "FinishAfter is the earliest time anyone can release it to the destination with EscrowFinish.",
                    "CancelAfter (optional) is when it can instead be returned to you with EscrowCancel.",
                    "Times are seconds since the Ripple epoch (1 January 2000), and the ledger uses its own close time, not your clock.",
                    "Escrows can also be locked by a crypto-condition, the basis for trustless cross-chain swaps.",
                ),
                code = """
                    {
                      "TransactionType": "EscrowCreate",
                      "Account": "rOwner…",
                      "Destination": "rDestination…",
                      "Amount": "${Transactions.parseXrp(amount) ?: 10_000_000}",
                      "FinishAfter": ${now + (release.toLongOrNull() ?: 1) * 60}
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/payment-types/escrow",
            )
        }
    }
}

@Composable
private fun EscrowRow(escrow: EscrowEntry, now: Long, onFinish: () -> Unit) {
    val finishAt = escrow.finishAfter
    val ready = finishAt == null || now > finishAt
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Format.xrp(escrow.drops), fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.Orange)
            Text("to ${shortAddr(escrow.destination)}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Text(
                if (ready) "Ready to release" else "Unlocks in ${formatDuration(finishAt!! - now)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) LabColors.Matrix else LabColors.TextMuted,
            )
        }
        LabButton("Release", onFinish, accent = LabColors.Matrix, enabled = ready && escrow.createSequence != null)
    }
}

private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 60}m ${s % 60}s"
}
