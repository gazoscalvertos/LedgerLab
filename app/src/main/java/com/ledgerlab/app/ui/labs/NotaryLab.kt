package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
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
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.Notary
import com.ledgerlab.xrpl.labs.NotaryRecord
import com.ledgerlab.xrpl.wallet.WalletKeys
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What's being notarised or checked: a picked file, or typed text. */
data class Fingerprint(val label: String, val sha256: String, val size: Int)

class NotaryViewModel(app: Application) : LabViewModel(app) {
    private val _records = MutableStateFlow<List<NotaryRecord>>(emptyList())
    /** The selected wallet's notarised hashes, newest first. */
    val records: StateFlow<List<NotaryRecord>> = _records.asStateFlow()

    private fun appQueries() = AppQueries(rpc(), XrplRpc(network.value.clioRpcUrl))

    override suspend fun load() {
        val wallet = selected.value ?: return
        _records.value = appQueries().notarizations(wallet.address)
    }

    fun notarize(print: Fingerprint) {
        if (_records.value.any { it.sha256 == print.sha256 }) {
            return fail("Notarise", "This wallet already notarised exactly this content. Its record is below.")
        }
        runTx("Notarise ${print.label}") { a, k, f -> LabTransactions.notarize(a, k, f, print.sha256, print.label) }
    }

    /** Looks for [print]'s hash in [address]'s history. Anyone can do this: it needs no wallet. */
    fun verify(print: Fingerprint, address: String) {
        val label = "Verify ${print.label}"
        if (!WalletKeys.isValidAddress(address)) return fail(label, "Enter the address that notarised it.")
        runWork(label) { step ->
            step("Reading ${shortAddr(address.trim())}'s history")
            val match = appQueries().notarizations(address.trim()).lastOrNull { it.sha256 == print.sha256 }
            if (match == null) {
                "No match. ${shortAddr(address.trim())} never notarised this exact content, or the record is older than its last 200 transactions. A single changed byte gives a different hash."
            } else {
                "Match. This content existed by ${match.closeTime?.let(::formatLedgerTime) ?: "ledger ${match.ledgerIndex}"}, " +
                    "proven by ledger ${match.ledgerIndex} (tx ${match.txHash.take(10)}…), recorded as \"${match.label}\"."
            }
        }
    }
}

private enum class NotaryTab(val label: String) { STAMP("Notarise"), CHECK("Verify"), HISTORY("Records") }

@Composable
fun NotaryLab(onBack: () -> Unit, vm: NotaryViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Gold
    var tab by rememberSaveable { mutableStateOf(NotaryTab.STAMP) }

    LabScaffold("Notary", "Prove a file existed, without revealing it", accent, network, vm::selectNetwork, onBack) {
        if (wallets.isEmpty()) {
            item { NeedsWallet(accent) }
        } else {
            item {
                Panel(accent = accent) {
                    Text("Acting as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotaryTab.entries.forEach { t -> LabChip(t.label, tab == t, LabColors.Cyan) { tab = t } }
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            when (tab) {
                NotaryTab.STAMP -> item {
                    FingerprintPanel("Notarise", accent, onError = { vm.clearTx() }) { print ->
                        LabButton("Notarise on ${network.label}", { vm.notarize(print) }, Modifier.fillMaxWidth(), accent)
                    }
                }
                NotaryTab.CHECK -> item {
                    var address by rememberSaveable(wallet?.address) { mutableStateOf(wallet?.address ?: "") }
                    FingerprintPanel("Verify", LabColors.Cyan, onError = { vm.clearTx() }) { print ->
                        LabTextField(address, { address = it.trim() }, "Notarised by (address)", Modifier.fillMaxWidth(), LabColors.Cyan, mono = true)
                        Spacer(Modifier.height(8.dp))
                        LabButton("Check the ledger", { vm.verify(print, address) }, Modifier.fillMaxWidth(), LabColors.Cyan)
                    }
                }
                NotaryTab.HISTORY -> item { RecordsPanel(records, network.explorerUrl, accent, vm::reload) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "The phone hashes the file with SHA-256. The file itself never leaves the device; only its 32-byte fingerprint does.",
                    "The hash goes into a Memo on an AccountSet that changes nothing, the cheapest transaction there is. MemoType marks it as a notary record and MemoFormat carries the file name.",
                    "Once validated, the ledger's close time is an independent timestamp: the content existed by then, and the wallet that signed vouches for it.",
                    "To verify, hash the file again and look for the same value in the signer's history. Change one byte and the hash no longer matches.",
                    "Mockup notes: a real service would index records server-side (account_tx only reaches back so far) and might anchor many hashes at once in a Merkle root.",
                ),
                code = """
                    {
                      "TransactionType": "AccountSet",
                      "Account": "rYou…",
                      "Memos": [{ "Memo": {
                        "MemoType": hex("${Notary.MEMO_TYPE}"),
                        "MemoData": "<sha-256 of the file>",
                        "MemoFormat": hex("contract.pdf")
                      }}]
                    }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/references/protocol/transactions/common-fields#memos-field",
            )
        }
    }
}

/** Pick a file (or type text), show its fingerprint, then [action] with it. */
@Composable
private fun FingerprintPanel(title: String, accent: Color, onError: () -> Unit, action: @Composable (Fingerprint) -> Unit) {
    var picked by remember { mutableStateOf<Fingerprint?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var pickError by remember { mutableStateOf<String?>(null) }
    val pick = rememberMediaPicker(
        onPicked = { media ->
            pickError = null
            onError()
            text = ""
            picked = Fingerprint(media.name, Notary.sha256Hex(media.bytes), media.bytes.size)
        },
        onError = { pickError = it },
        mimeTypes = arrayOf("*/*"),
    )
    val typed = remember(text) { text.takeIf { it.isNotBlank() }?.let { Fingerprint("text note", Notary.sha256Hex(it.toByteArray()), it.toByteArray().size) } }
    val print = typed ?: picked
    Panel(accent = accent, title = title) {
        LabOutlineButton("Pick any file", pick, Modifier.fillMaxWidth(), accent)
        Spacer(Modifier.height(8.dp))
        LabTextField(text, { text = it.take(2_000) }, "…or type some text", Modifier.fillMaxWidth(), accent)
        ErrorLine(pickError)
        if (print != null) {
            Spacer(Modifier.height(10.dp))
            KeyValue("Content", print.label)
            KeyValue("Size", if (print.size >= 1024) "${print.size / 1024} KB" else "${print.size} bytes")
            Spacer(Modifier.height(4.dp))
            Text("SHA-256", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            Text(print.sha256, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = accent)
            Spacer(Modifier.height(10.dp))
            action(print)
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Hashing happens on the phone, so even large or private files are fine.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
    }
}

@Composable
private fun RecordsPanel(records: List<NotaryRecord>, explorerUrl: String, accent: Color, onRefresh: () -> Unit) {
    val uri = LocalUriHandler.current
    Panel(accent = accent, title = "Notarised by this wallet (${records.size})") {
        if (records.isEmpty()) Text("Nothing yet. Notarise a file and it shows up here.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            records.forEach { r ->
                Column(Modifier.fillMaxWidth().clickable(enabled = r.txHash.isNotEmpty()) { uri.openUri("$explorerUrl/transactions/${r.txHash}") }.padding(vertical = 2.dp)) {
                    Text(r.label.ifBlank { "untitled" }, fontWeight = FontWeight.SemiBold, color = LabColors.TextPrimary)
                    Text(r.sha256.take(16) + "…" + r.sha256.takeLast(8), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = accent)
                    Text(
                        (r.closeTime?.let(::formatLedgerTime) ?: "") + " · ledger ${r.ledgerIndex}",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LabOutlineButton("Refresh", onRefresh, Modifier.fillMaxWidth(), accent)
    }
}
