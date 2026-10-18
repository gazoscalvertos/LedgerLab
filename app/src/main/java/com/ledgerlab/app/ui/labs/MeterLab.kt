package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.PayChannel
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.WalletKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A running (or paused) metered session on one channel. Claims are cumulative, so only the latest matters. */
data class Meter(
    val channelId: String,
    val dropsPerMinute: Long,
    val running: Boolean,
    val seconds: Long,
    /** What the payee may take in total, including anything already redeemed before this session. */
    val claimDrops: Long,
    /** What this session has added on top of the channel's balance. */
    val sessionDrops: Long,
    val signature: String?,
    val claimsSigned: Int,
    val lastSignMicros: Long,
    val stoppedBecause: String? = null,
)

class MeterViewModel(app: Application) : LabViewModel(app) {
    private val _channels = MutableStateFlow<List<PayChannel>>(emptyList())
    /** Open channels the selected wallet pays into. */
    val channels: StateFlow<List<PayChannel>> = _channels.asStateFlow()

    private val _meter = MutableStateFlow<Meter?>(null)
    val meter: StateFlow<Meter?> = _meter.asStateFlow()

    private var ticking: Job? = null

    override suspend fun load() {
        val wallet = selected.value ?: return
        _channels.value = queries().channels(wallet.address).filter { it.payer == wallet.address && it.expiration == null }
    }

    /** A channel sized for a session: [amount] XRP locked up front, 60 s settle delay. */
    fun openChannel(payee: StoredWallet, amount: String) {
        val drops = Transactions.parseXrp(amount) ?: return fail("Open a channel", "Enter an XRP amount.")
        runTx("Open a ${Format.xrp(drops)} channel to ${payee.label}") { a, k, f ->
            LabTransactions.channelCreate(a, k, f, payee.address, drops, 60)
        }
    }

    /**
     * Starts the meter: once a second the payer signs a claim for everything owed so far. Each claim is
     * a signature made on this phone, with no transaction and no fee.
     */
    fun start(channel: PayChannel, ratePerMinute: String) {
        val label = "Start the meter"
        val payer = selected.value?.takeIf { it.address == channel.payer } ?: return fail(label, "Act as the channel's payer to run the meter.")
        val rate = Transactions.parseXrp(ratePerMinute)?.takeIf { it > 0 } ?: return fail(label, "Enter a rate in XRP per minute.")
        val previous = _meter.value?.takeIf { it.channelId == channel.id }
        val base = maxOf(channel.balanceDrops, previous?.claimDrops ?: 0L) - (previous?.sessionDrops ?: 0L)
        val startSeconds = previous?.seconds ?: 0L
        ticking?.cancel()
        clearTx()
        ticking = viewModelScope.launch {
            val secret = withContext(Dispatchers.IO) { walletStore.secretOf(payer) }
            val began = System.currentTimeMillis()
            _meter.value = (previous ?: Meter(channel.id, rate, true, 0, base, 0, null, 0, 0)).copy(dropsPerMinute = rate, running = true, stoppedBecause = null)
            while (isActive) {
                delay(1_000)
                val seconds = startSeconds + (System.currentTimeMillis() - began) / 1_000
                // Rounded per second, so rate changes on resume don't rewrite what was already owed.
                val owed = (previous?.sessionDrops ?: 0L) + rate * (seconds - startSeconds) / 60
                val total = base + owed
                if (total > channel.amountDrops) {
                    _meter.value = _meter.value?.copy(running = false, stoppedBecause = "The channel ran out. Settle, or add funds in the Channels lab.")
                    break
                }
                val started = System.nanoTime()
                val signature = withContext(Dispatchers.Default) { WalletKeys.signClaim(secret, channel.id, total) }
                val micros = (System.nanoTime() - started) / 1_000
                _meter.value = _meter.value?.copy(
                    seconds = seconds,
                    claimDrops = total,
                    sessionDrops = owed,
                    signature = signature,
                    claimsSigned = (_meter.value?.claimsSigned ?: 0) + 1,
                    lastSignMicros = micros,
                )
            }
        }
    }

    fun stop() {
        ticking?.cancel()
        _meter.value = _meter.value?.copy(running = false)
    }

    /** The payee checks the latest claim with channel_verify, as they would before delivering more. */
    fun verify(channel: PayChannel) {
        val m = _meter.value?.takeIf { it.channelId == channel.id && it.signature != null } ?: return fail("Verify claim", "Run the meter first.")
        runWork("Verify claim") { step ->
            step("Asking ${network.value.label} with channel_verify")
            if (queries().verifyClaim(channel.id, m.claimDrops, m.signature!!, channel.publicKey)) {
                "Valid: the payee can take ${preciseXrp(m.claimDrops)} whenever they like."
            } else {
                "The server says this claim is not valid."
            }
        }
    }

    /** One transaction, signed by the payee, settles the whole session. */
    fun settle(channel: PayChannel, close: Boolean) {
        val label = if (close) "Settle and close" else "Settle"
        stop()
        val m = _meter.value?.takeIf { it.channelId == channel.id && it.signature != null } ?: return fail(label, "Run the meter first.")
        val payee = wallets.value.firstOrNull { it.address == channel.payee } ?: return fail(label, "The payee's wallet isn't on this phone.")
        runTx("$label: ${preciseXrp(m.sessionDrops)} for ${formatDuration(m.seconds)}", wallet = payee) { a, k, f ->
            LabTransactions.channelClaim(a, k, f, channel.id, m.claimDrops, m.signature, channel.publicKey, close)
        }
        // The meter stays: claims are running totals, so resuming after a settle just carries on from here.
    }
}

private fun formatDuration(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun MeterLab(onBack: () -> Unit, vm: MeterViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val meter by vm.meter.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Cyan
    var rate by rememberSaveable { mutableStateOf("0.6") }
    var deposit by rememberSaveable { mutableStateOf("10") }
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    val others = wallets.filter { it.id != wallet?.id }
    var payeeId by rememberSaveable(wallet?.id) { mutableStateOf(others.firstOrNull()?.id) }
    val channel = channels.firstOrNull { it.id == (meter?.channelId ?: chosenId) } ?: channels.firstOrNull()

    LabScaffold("Pay-per-minute", "Stream XRP by the second, settle once", accent, network, vm::selectNetwork, onBack) {
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "A metered session has a payer and a provider. Create and fund two wallets in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent, title = "Payer") {
                    WalletChips(wallets, wallet, accent, { if (meter?.running != true) vm.selectWallet(it.id) })
                    if (meter?.running == true) Text("Stop the meter to switch wallet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            if (channels.isEmpty()) {
                item {
                    Panel(accent = accent, title = "Open a channel") {
                        Text("The payer locks XRP in a channel to the provider once. Everything after that is off the ledger.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text("Provider", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                        WalletChips(others, others.firstOrNull { it.id == payeeId }, LabColors.Gold, { payeeId = it.id })
                        Spacer(Modifier.height(8.dp))
                        LabTextField(deposit, { deposit = it }, "Deposit (XRP)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                        Spacer(Modifier.height(10.dp))
                        LabButton("Open channel", { others.firstOrNull { it.id == payeeId }?.let { vm.openChannel(it, deposit) } }, Modifier.fillMaxWidth(), accent, enabled = payeeId != null)
                    }
                }
            } else if (channel != null) {
                item {
                    Panel(accent = accent, title = "Channel") {
                        if (channels.size > 1 && meter == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                channels.forEach { c -> LabChip("to ${nameOf(c.payee, wallets)}", c.id == channel.id, accent) { chosenId = c.id } }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        KeyValue("Provider", nameOf(channel.payee, wallets))
                        KeyValue("Deposit", Format.xrp(channel.amountDrops))
                        KeyValue("Already settled", preciseXrp(channel.balanceDrops))
                    }
                }
                item { MeterPanel(vm, channel, meter, rate, { rate = it }, accent) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "Pay-per-minute suits calls, video, parking, EV charging or GPU time: anything billed while it runs.",
                    "The payer opens a payment channel once, locking a deposit. That's the only transaction the payer sends.",
                    "Every second the payer's phone signs a claim for the running total: \"the provider may take N drops from this channel\". A claim is just a signature, so it's free and takes about a millisecond.",
                    "Claims are cumulative, so the provider only keeps the latest. If the payer stops paying, the provider stops the service and loses at most a second's worth.",
                    "When the session ends the provider submits one PaymentChannelClaim with the last signature and gets the whole amount. Thousands of payments, one ledger fee.",
                ),
                code = """
                    // every second, off the ledger
                    sign("CLM\0" + channelId + uint64(totalDrops))

                    // once, at the end
                    {
                      "TransactionType": "PaymentChannelClaim",
                      "Account": "rProvider…",
                      "Channel": "5DB0…",
                      "Balance": "<totalDrops>",
                      "Signature": "<latest claim>",
                      "PublicKey": "<payer's key>"
                    }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/payment-types/payment-channels",
            )
        }
    }
}

@Composable
private fun MeterPanel(vm: MeterViewModel, channel: PayChannel, meter: Meter?, rate: String, onRate: (String) -> Unit, accent: androidx.compose.ui.graphics.Color) {
    val m = meter?.takeIf { it.channelId == channel.id }
    Panel(accent = if (m?.running == true) LabColors.Matrix else accent, title = "Meter") {
        Text(formatDuration(m?.seconds ?: 0), fontFamily = Mono, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = if (m?.running == true) LabColors.Matrix else LabColors.TextPrimary)
        Text(preciseXrp(m?.sessionDrops ?: 0), fontFamily = Mono, fontSize = 22.sp, color = LabColors.Gold)
        Spacer(Modifier.height(8.dp))
        val used = (m?.claimDrops ?: channel.balanceDrops).toFloat() / channel.amountDrops.coerceAtLeast(1)
        LinearProgressIndicator(progress = { used.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = LabColors.Gold, trackColor = LabColors.Outline)
        Text("${(used * 100).toInt()}% of the deposit", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        Spacer(Modifier.height(10.dp))
        if (m != null) {
            KeyValue("Claims signed", "${m.claimsSigned}")
            KeyValue("Last signature took", "%.2f ms".format(m.lastSignMicros / 1000.0))
            KeyValue("Ledger transactions", "0")
            m.signature?.let { Text(it.take(32) + "…", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted) }
            m.stoppedBecause?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LabColors.Danger) }
            Spacer(Modifier.height(10.dp))
        }
        if (m?.running == true) {
            LabButton("Stop", vm::stop, Modifier.fillMaxWidth(), LabColors.Danger)
        } else {
            LabTextField(rate, onRate, "Rate (XRP per minute)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
            Spacer(Modifier.height(8.dp))
            LabButton(if (m == null) "Start the meter" else "Resume", { vm.start(channel, rate) }, Modifier.fillMaxWidth(), LabColors.Matrix)
        }
        if (m?.signature != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabOutlineButton("Verify", { vm.verify(channel) }, Modifier.weight(1f), accent)
                LabButton("Settle", { vm.settle(channel, close = false) }, Modifier.weight(1f), LabColors.Gold)
            }
            Spacer(Modifier.height(8.dp))
            LabOutlineButton("Settle and close the channel", { vm.settle(channel, close = true) }, Modifier.fillMaxWidth(), LabColors.Gold)
            Text("Settling is signed by the provider's wallet in one transaction.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
