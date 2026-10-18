package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.StreamParser
import com.ledgerlab.xrpl.StreamTx
import com.ledgerlab.xrpl.labs.LabQueries
import com.ledgerlab.xrpl.labs.TrustLine
import com.ledgerlab.xrpl.wallet.AccountState
import com.ledgerlab.xrpl.wallet.WalletKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

sealed interface ExplorerResult {
    data object Empty : ExplorerResult
    data object Loading : ExplorerResult
    data class Error(val message: String) : ExplorerResult
    data class Account(
        val address: String,
        val state: AccountState?,
        val lines: List<TrustLine>,
        val history: List<StreamTx>,
    ) : ExplorerResult
    data class Tx(val summary: StreamTx?, val validated: Boolean, val json: String, val hash: String) : ExplorerResult
    data class Ledger(val index: Long?, val hash: String?, val txCount: Int?, val closeTime: String?, val json: String) : ExplorerResult
}

class ExplorerViewModel(app: Application) : LabViewModel(app) {
    private val _result = MutableStateFlow<ExplorerResult>(ExplorerResult.Empty)
    val result: StateFlow<ExplorerResult> = _result.asStateFlow()
    private var lastQuery: String? = null
    private var searchJob: Job? = null

    override suspend fun load() {
        // Re-run the last search when the network changes.
        lastQuery?.let { withContext(Dispatchers.Main) { search(it) } }
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        lastQuery = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _result.value = ExplorerResult.Loading
            _result.value = try {
                withContext(Dispatchers.IO) { lookup(q) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExplorerResult.Error(e.message ?: "Lookup failed")
            }
        }
    }

    private fun lookup(q: String): ExplorerResult {
        val queries = queries()
        return when {
            WalletKeys.isValidAddress(q) -> {
                val rpc = rpc()
                val state = rpc.accountInfo(q)
                ExplorerResult.Account(
                    address = q,
                    state = state,
                    lines = if (state != null) queries.trustLines(q) else emptyList(),
                    history = if (state != null) rpc.accountTx(q) else emptyList(),
                )
            }
            q.length == 64 && q.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' } -> {
                val tx = queries.tx(q.uppercase())
                ExplorerResult.Tx(
                    summary = StreamParser.parseTx(tx),
                    validated = tx["validated"]?.toString() == "true",
                    json = LabQueries.prettyJson(tx),
                    hash = q.uppercase(),
                )
            }
            q.all { it.isDigit() } || q == "validated" -> {
                val result = queries.ledger(q)
                val ledger = result["ledger"] as? JsonObject
                ExplorerResult.Ledger(
                    index = (result["ledger_index"] ?: ledger?.get("ledger_index"))?.toString()?.trim('"')?.toLongOrNull(),
                    hash = (result["ledger_hash"] ?: ledger?.get("ledger_hash"))?.toString()?.trim('"'),
                    txCount = null,
                    closeTime = ledger?.get("close_time_human")?.toString()?.trim('"'),
                    json = LabQueries.prettyJson(ledger ?: result),
                )
            }
            else -> ExplorerResult.Error("Enter an r-address, a 64-character transaction hash, or a ledger number.")
        }
    }
}

@Composable
fun ExplorerLab(onBack: () -> Unit, vm: ExplorerViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val accent = LabColors.TextPrimary
    var query by rememberSaveable { mutableStateOf("") }

    fun go(q: String) {
        query = q
        vm.search(q)
    }

    LabScaffold("Explorer", "Look up any account, transaction or ledger", LabColors.Cyan, network, vm::selectNetwork, onBack) {
        item {
            Panel(accent = LabColors.Cyan, title = "Search ${network.label}") {
                LabTextField(query, { query = it }, "Address, tx hash or ledger #", Modifier.fillMaxWidth(), LabColors.Cyan, mono = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("Search", { go(query) }, Modifier.weight(1f), LabColors.Cyan, enabled = query.isNotBlank())
                    LabButton("Latest ledger", { go("validated") }, Modifier.weight(1f), LabColors.Violet)
                }
                if (wallets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        wallets.forEach { w -> LabChip(w.label, false, LabColors.Cyan) { go(w.address) } }
                    }
                }
            }
        }
        item { ResultView(result, network.explorerUrl, onOpen = ::go) }
        item {
            HowItWorks(
                steps = listOf(
                    "Every lookup here is a plain JSON-RPC call to a public rippled server. No indexer or API key is involved.",
                    "Accounts come from account_info, account_lines and account_tx.",
                    "Transactions come from tx, including the metadata that records exactly what changed.",
                    "Ledgers come from ledger. Each one closes every 3 to 5 seconds and is chained to the last by its hash.",
                ),
                code = """
                    POST ${network.rpcUrl}
                    {
                      "method": "tx",
                      "params": [{ "transaction": "${(result as? ExplorerResult.Tx)?.hash ?: "E3FE6EA3…"}" }]
                    }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/references/http-websocket-apis",
            )
        }
    }
}

@Composable
private fun ResultView(result: ExplorerResult, explorerUrl: String, onOpen: (String) -> Unit) {
    val uri = LocalUriHandler.current
    when (result) {
        ExplorerResult.Empty -> Unit
        ExplorerResult.Loading -> Text("Looking it up…", color = LabColors.TextSecondary)
        is ExplorerResult.Error -> Text(result.message, color = LabColors.Danger, style = MaterialTheme.typography.bodySmall)
        is ExplorerResult.Account -> Panel(accent = LabColors.Matrix, title = "Account") {
            Text(result.address, fontFamily = Mono, fontSize = 12.sp, color = LabColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            val s = result.state
            if (s == null) {
                Text("Not found on this network (never funded, or deleted).", color = LabColors.Gold, style = MaterialTheme.typography.bodySmall)
            } else {
                KeyValue("Balance", Format.xrp(s.balanceDrops), LabColors.Matrix)
                KeyValue("Sequence", s.sequence.toString())
                KeyValue("Owned objects", s.ownerCount.toString())
                if (result.lines.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("TRUST LINES", style = MaterialTheme.typography.labelSmall, color = LabColors.Gold)
                    result.lines.forEach { KeyValue("${it.currency} · ${shortAddr(it.issuer)}", it.balance) }
                }
                Spacer(Modifier.height(8.dp))
                Text("RECENT TRANSACTIONS", style = MaterialTheme.typography.labelSmall, color = LabColors.Cyan)
                result.history.forEach { tx ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(tx.hash) }.padding(vertical = 4.dp)) {
                        Text(tx.type, color = LabColors.category(tx.category), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(Format.amount(tx.amount), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ExternalLink("Open on the web explorer") { uri.openUri("$explorerUrl/accounts/${result.address}") }
        }
        is ExplorerResult.Tx -> Panel(accent = LabColors.Cyan, title = "Transaction") {
            result.summary?.let { tx ->
                KeyValue("Type", tx.type, LabColors.category(tx.category))
                KeyValue("Result", tx.result, if (tx.succeeded) LabColors.Matrix else LabColors.Danger)
                KeyValue("Validated", if (result.validated) "yes" else "not yet")
                KeyValue("From", shortAddr(tx.account))
                tx.destination?.let { KeyValue("To", shortAddr(it)) }
                if (tx.amount != null) KeyValue("Amount", Format.amount(tx.amount))
                KeyValue("Fee", "${tx.feeDrops} drops")
                KeyValue("Ledger", tx.ledgerIndex.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExternalLink("Sender") { onOpen(tx.account) }
                    tx.destination?.let { d -> ExternalLink("Destination") { onOpen(d) } }
                    if (tx.ledgerIndex > 0) ExternalLink("Ledger") { onOpen(tx.ledgerIndex.toString()) }
                }
            }
            Spacer(Modifier.height(8.dp))
            JsonBlock(result.json)
            Spacer(Modifier.height(8.dp))
            ExternalLink("Open on the web explorer") { uri.openUri("$explorerUrl/transactions/${result.hash}") }
        }
        is ExplorerResult.Ledger -> Panel(accent = LabColors.Violet, title = "Ledger") {
            result.index?.let { KeyValue("Index", Format.grouped(it), LabColors.Violet) }
            result.closeTime?.let { KeyValue("Closed", it) }
            result.hash?.let { KeyValue("Hash", it.take(16) + "…") }
            Spacer(Modifier.height(8.dp))
            JsonBlock(result.json)
            result.index?.let { idx ->
                Spacer(Modifier.height(8.dp))
                ExternalLink("Open on the web explorer") { uri.openUri("$explorerUrl/ledgers/$idx") }
            }
        }
    }
}

@Composable
private fun JsonBlock(json: String) {
    Text(
        json.take(6_000),
        fontFamily = Mono,
        fontSize = 10.sp,
        color = LabColors.Matrix,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    )
}

@Composable
private fun ExternalLink(text: String, onClick: () -> Unit) {
    Text(text, color = LabColors.Cyan, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp))
}
