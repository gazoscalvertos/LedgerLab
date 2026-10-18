package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The latest claim the payer signed for a channel: the payee may take [drops] in total. */
data class SignedClaim(val drops: Long, val signature: String)

class ChannelsViewModel(app: Application) : LabViewModel(app) {
    private val _channels = MutableStateFlow<List<PayChannel>>(emptyList())
    val channels: StateFlow<List<PayChannel>> = _channels.asStateFlow()

    // Kept across wallet switches, so you can sign as the payer and then redeem as the payee.
    private val _claims = MutableStateFlow<Map<String, SignedClaim>>(emptyMap())
    val claims: StateFlow<Map<String, SignedClaim>> = _claims.asStateFlow()

    override suspend fun load() {
        val wallet = selected.value ?: return
        _channels.value = queries().channels(wallet.address)
    }

    fun open(destination: String, amount: String, settleDelay: String) {
        val label = "Open a payment channel"
        if (!WalletKeys.isValidAddress(destination)) return fail(label, "Enter a valid destination address.")
        if (destination.trim() == selected.value?.address) return fail(label, "Pick a different wallet to pay.")
        val drops = Transactions.parseXrp(amount) ?: return fail(label, "Enter an XRP amount.")
        val delay = settleDelay.toLongOrNull()?.takeIf { it in 0..2_592_000 } ?: return fail(label, "Settle delay is in seconds (up to 30 days).")
        runTx(label) { a, k, f -> LabTransactions.channelCreate(a, k, f, destination.trim(), drops, delay) }
    }

    /** Signs a bigger claim on the device. No transaction, no fee: this is what makes channels fast. */
    fun pay(channel: PayChannel, stepXrp: String) {
        val label = "Sign a claim"
        val wallet = selected.value?.takeIf { it.address == channel.payer } ?: return fail(label, "Only the payer can sign claims.")
        val step = Transactions.parseXrp(stepXrp) ?: return fail(label, "Enter an XRP amount per payment.")
        val current = _claims.value[channel.id]?.drops ?: channel.balanceDrops
        val next = (current + step).coerceAtMost(channel.amountDrops)
        if (next <= current) return fail(label, "The channel is used up. Add funds to keep paying.")
        runWork(label) { onStep ->
            onStep("Signing off the ledger")
            val started = System.nanoTime()
            val signature = WalletKeys.signClaim(walletStore.secretOf(wallet), channel.id, next)
            val micros = (System.nanoTime() - started) / 1_000
            _claims.value = _claims.value + (channel.id to SignedClaim(next, signature))
            "Claim for ${Format.xrp(next)} in total, signed in ${micros / 1000.0} ms. Nothing touched the ledger."
        }
    }

    fun verify(channel: PayChannel) {
        val claim = _claims.value[channel.id] ?: return fail("Verify claim", "Sign a claim first.")
        runWork("Verify claim") { onStep ->
            onStep("Asking ${network.value.label} with channel_verify")
            val ok = queries().verifyClaim(channel.id, claim.drops, claim.signature, channel.publicKey)
            if (ok) "The signature is good for ${Format.xrp(claim.drops)}." else "The server says this signature is not valid."
        }
    }

    /** The payee cashes the latest claim, optionally closing the channel in the same transaction. */
    fun redeem(channel: PayChannel, close: Boolean) {
        val label = if (close) "Redeem and close" else "Redeem claim"
        val claim = _claims.value[channel.id] ?: return fail(label, "There's no signed claim for this channel yet. Switch to the payer and send some payments.")
        if (selected.value?.address != channel.payee) return fail(label, "Switch to the payee's wallet to redeem.")
        runTx(label) { a, k, f ->
            LabTransactions.channelClaim(a, k, f, channel.id, claim.drops, claim.signature, channel.publicKey, close)
        }
    }

    fun fund(channel: PayChannel, amount: String) {
        val drops = Transactions.parseXrp(amount) ?: return fail("Add funds", "Enter an XRP amount.")
        runTx("Add ${Format.xrp(drops)} to the channel") { a, k, f -> LabTransactions.channelFund(a, k, f, channel.id, drops) }
    }

    fun close(channel: PayChannel) = runTx("Close channel") { a, k, f -> LabTransactions.channelClaim(a, k, f, channel.id, close = true) }
}

@Composable
fun ChannelsLab(onBack: () -> Unit, vm: ChannelsViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val claims by vm.claims.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Matrix

    val otherWallet = wallets.firstOrNull { it.id != wallet?.id }
    var destination by rememberSaveable(otherWallet?.address) { mutableStateOf(otherWallet?.address ?: "") }
    var amount by rememberSaveable { mutableStateOf("20") }
    var delay by rememberSaveable { mutableStateOf("60") }
    var step by rememberSaveable { mutableStateOf("0.25") }
    var topUp by rememberSaveable { mutableStateOf("5") }

    LabScaffold("Payment channels", "Streaming XRP micropayments off the ledger", accent, network, vm::selectNetwork, onBack) {
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "A channel has a payer and a payee. Create and fund two wallets in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent, title = "Acting as") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                }
            }
            item {
                Panel(accent = accent, title = "Open a channel") {
                    Text("Pay", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    val others = wallets.filter { it.id != wallet?.id }
                    WalletChips(others, others.firstOrNull { it.address == destination }, LabColors.Cyan, { destination = it.address })
                    Spacer(Modifier.height(8.dp))
                    LabTextField(destination, { destination = it.trim() }, "Payee address", Modifier.fillMaxWidth(), accent, mono = true)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabTextField(amount, { amount = it }, "Deposit (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                        LabTextField(delay, { delay = it }, "Settle delay (s)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
                    }
                    Spacer(Modifier.height(10.dp))
                    LabButton("Open channel", { vm.open(destination, amount, delay) }, Modifier.fillMaxWidth(), accent)
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item {
                Panel(accent = accent, title = "Channels for ${wallet?.label ?: "this wallet"}") {
                    ErrorLine(error)
                    if (channels.isEmpty()) Text("No channels yet. Open one above.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        channels.forEach { ch ->
                            ChannelCard(
                                channel = ch,
                                me = wallet?.address,
                                claim = claims[ch.id],
                                step = step,
                                onStep = { step = it },
                                topUp = topUp,
                                onTopUp = { topUp = it },
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "PaymentChannelCreate locks XRP in a channel from payer to payee. That's one ledger transaction.",
                    "After that the payer signs claims on the device: \"the payee may take N XRP in total\". Each claim replaces the last.",
                    "Claims are just signatures, so thousands per second cost nothing. The payee can check one with channel_verify.",
                    "Whenever it likes, the payee submits the latest claim with PaymentChannelClaim and receives the difference.",
                    "The payer can close, but the settle delay gives the payee time to cash its last claim first.",
                    "Channels hold XRP only. Tokens can't go in a channel; use escrow or a check for those.",
                ),
                code = """
                    // Off ledger: the payer signs "CLM\0" + channel ID + amount
                    {
                      "TransactionType": "PaymentChannelClaim",
                      "Account": "rPayee…",
                      "Channel": "${channels.firstOrNull()?.id?.take(16) ?: "5DB0…"}…",
                      "Balance": "${claims.values.firstOrNull()?.drops ?: 1_000_000}",
                      "Signature": "${claims.values.firstOrNull()?.signature?.take(16) ?: "30440220…"}…",
                      "PublicKey": "payer's key"
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/payment-types/payment-channels",
            )
        }
    }
}

@Composable
private fun ChannelCard(
    channel: PayChannel,
    me: String?,
    claim: SignedClaim?,
    step: String,
    onStep: (String) -> Unit,
    topUp: String,
    onTopUp: (String) -> Unit,
    vm: ChannelsViewModel,
) {
    val payer = channel.payer == me
    val promised = claim?.drops ?: channel.balanceDrops
    Column {
        Row {
            Text(
                if (payer) "→ ${shortAddr(channel.payee)}" else "← ${shortAddr(channel.payer)}",
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                color = if (payer) LabColors.Cyan else LabColors.Matrix,
                modifier = Modifier.weight(1f),
            )
            Text(Format.xrp(channel.amountDrops), fontFamily = Mono, color = LabColors.TextPrimary)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (channel.amountDrops == 0L) 0f else promised.toFloat() / channel.amountDrops },
            modifier = Modifier.fillMaxWidth(),
            color = LabColors.Matrix,
            trackColor = LabColors.Outline,
        )
        Spacer(Modifier.height(6.dp))
        KeyValue("Paid out on ledger", Format.xrp(channel.balanceDrops))
        KeyValue("Latest signed claim", claim?.let { Format.xrp(it.drops) } ?: "none", if (claim != null) LabColors.Matrix else LabColors.TextMuted)
        if (claim != null && claim.drops > channel.balanceDrops) {
            KeyValue("Owed off ledger", Format.xrp(claim.drops - channel.balanceDrops), LabColors.Gold)
        }
        channel.expiration?.let { closeAt ->
            KeyValue("Closing after", "${formatRipple(closeAt)} (settle delay)", LabColors.Orange)
        }
        Spacer(Modifier.height(8.dp))
        if (payer) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabTextField(step, onStep, "Per payment (XRP)", Modifier.weight(1f), LabColors.Cyan, keyboardType = KeyboardType.Decimal)
                LabButton("Pay", { vm.pay(channel, step) }, Modifier.weight(1f), LabColors.Cyan)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabTextField(topUp, onTopUp, "Top up (XRP)", Modifier.weight(1f), LabColors.Cyan, keyboardType = KeyboardType.Decimal)
                LabOutlineButton("Add funds", { vm.fund(channel, topUp) }, Modifier.weight(1f), LabColors.Cyan)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabOutlineButton("Verify claim", { vm.verify(channel) }, Modifier.weight(1f), LabColors.Matrix, enabled = claim != null)
                LabOutlineButton("Close", { vm.close(channel) }, Modifier.weight(1f), LabColors.Danger)
            }
        } else {
            if (claim == null) {
                Text(
                    "No claim yet. Switch to the payer's wallet and tap Pay a few times, then come back to cash it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabButton("Redeem", { vm.redeem(channel, close = false) }, Modifier.weight(1f), LabColors.Matrix, enabled = claim != null)
                LabOutlineButton("Redeem + close", { vm.redeem(channel, close = true) }, Modifier.weight(1f), LabColors.Danger, enabled = claim != null)
            }
        }
    }
}

private fun formatRipple(seconds: Long): String {
    val left = seconds - rippleNow()
    return if (left <= 0) "now" else "${left / 60}m ${left % 60}s"
}
