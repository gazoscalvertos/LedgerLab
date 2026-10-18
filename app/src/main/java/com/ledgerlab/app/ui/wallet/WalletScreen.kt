package com.ledgerlab.app.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabOutlineButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.components.networkColor
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.StreamTx
import com.ledgerlab.xrpl.XrplNetwork
import com.ledgerlab.xrpl.wallet.KeyAlgorithm

@Composable
fun WalletScreen(
    state: WalletUiState,
    vm: WalletViewModel,
    onBack: () -> Unit,
) {
    val accent = networkColor(state.network)
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var revealFor by remember { mutableStateOf<StoredWallet?>(null) }
    var deleteFor by remember { mutableStateOf<StoredWallet?>(null) }

    LabScaffold(
        title = "Wallet",
        subtitle = "Keys, balances and XRP payments",
        accent = accent,
        network = state.network,
        onSelectNetwork = vm::selectNetwork,
        onBack = onBack,
    ) {
        state.notice?.let { notice ->
            item { NoticeBanner(notice, accent, vm::dismissNotice) }
        }

        if (state.wallets.isEmpty()) {
            item { EmptyWallets(accent, onCreate = { showCreate = true }, onImport = { showImport = true }) }
        } else {
            item {
                WalletPicker(
                    wallets = state.wallets,
                    selected = state.selected,
                    accent = accent,
                    onSelect = vm::selectWallet,
                    onAdd = { showCreate = true },
                    onImport = { showImport = true },
                )
            }
            state.selected?.let { wallet ->
                item { AccountCard(state, wallet, accent, vm) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabOutlineButton("Show secret", { revealFor = wallet }, Modifier.weight(1f), accent = LabColors.Gold)
                        LabOutlineButton("Remove", { deleteFor = wallet }, Modifier.weight(1f), accent = LabColors.Danger)
                    }
                }
                item { SendPanel(state, accent, vm) }
                item { HistoryPanel(state, wallet.address, accent) }
            }
        }

        item {
            HowItWorks(
                steps = listOf(
                    "A wallet is a key pair. LedgerLab generates it on the phone and keeps the family seed (the \"secret\") " +
                        "encrypted with a key held in the Android Keystore.",
                    "The address (r…) is derived from the public key. It doesn't exist on a network until it receives " +
                        "the base reserve, which the Testnet faucet covers for free.",
                    "To send, the app autofills Sequence (from account_info), Fee (from the fee command) and " +
                        "LastLedgerSequence (current ledger + 20), so a stuck transaction expires instead of hanging.",
                    "The Payment is serialized to the XRPL binary format and signed locally. Only the signed blob goes to the server via submit.",
                    "A preliminary result like tesSUCCESS isn't final. The app polls tx until the payment is in a validated ledger.",
                ),
                code = """
                    {
                      "TransactionType": "Payment",
                      "Account": "${state.selected?.address ?: "rYourAddress…"}",
                      "Destination": "rDestination…",
                      "Amount": "1000000",        // drops: 1 XRP
                      "DestinationTag": 42,       // optional
                      "Fee": "12",
                      "Sequence": 1234,
                      "LastLedgerSequence": 5678,
                      "SigningPubKey": "ED…",
                      "TxnSignature": "…"
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/accounts and xrpl.org/docs/concepts/transactions",
            )
        }
    }

    if (showCreate) CreateDialog(accent, onDismiss = { showCreate = false }) { label, alg ->
        vm.create(label, alg)
        showCreate = false
    }
    if (showImport) ImportDialog(accent, onDismiss = { showImport = false }, onImport = vm::import)
    revealFor?.let { wallet -> RevealDialog(wallet, remember(wallet.id) { vm.revealSecret(wallet) }) { revealFor = null } }
    deleteFor?.let { wallet ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Remove ${wallet.label}?") },
            text = { Text("The secret is deleted from this phone. Unless you've saved it elsewhere, any funds become unreachable.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(wallet); deleteFor = null }) { Text("Remove", color = LabColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
            containerColor = LabColors.SurfaceHigh,
        )
    }
}

@Composable
private fun NoticeBanner(text: String, accent: Color, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(6_000)
        onDismiss()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onDismiss)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyWallets(accent: Color, onCreate: () -> Unit, onImport: () -> Unit) {
    Panel(accent = accent, title = "No wallet yet") {
        Text(
            "Create a fresh wallet to play with on Testnet, or import one you already have with its secret.",
            style = MaterialTheme.typography.bodyMedium,
            color = LabColors.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabButton("Create wallet", onCreate, Modifier.weight(1f), accent = accent)
            LabOutlineButton("Import", onImport, Modifier.weight(1f), accent = accent)
        }
    }
}

@Composable
private fun WalletPicker(
    wallets: List<StoredWallet>,
    selected: StoredWallet?,
    accent: Color,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        wallets.forEach { w ->
            val isSelected = w.id == selected?.id
            Chip(w.label, if (isSelected) accent else LabColors.TextSecondary, isSelected) { onSelect(w.id) }
        }
        Chip("+ New", LabColors.TextSecondary, false, onAdd)
        Chip("Import", LabColors.TextSecondary, false, onImport)
    }
}

@Composable
private fun Chip(text: String, color: Color, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = color,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(shape)
            .background(if (filled) color.copy(alpha = 0.15f) else LabColors.Surface)
            .border(1.dp, if (filled) color.copy(alpha = 0.5f) else LabColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun AccountCard(state: WalletUiState, wallet: StoredWallet, accent: Color, vm: WalletViewModel) {
    val clipboard = LocalClipboardManager.current
    Panel(accent = accent, title = wallet.label, trailing = {
        IconButton(onClick = vm::refresh, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = accent)
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                wallet.address,
                fontFamily = Mono,
                fontSize = 13.sp,
                color = LabColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(wallet.address)) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy address", tint = LabColors.TextSecondary)
            }
        }
        Text("${wallet.algorithm.label} key", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        Spacer(Modifier.height(14.dp))

        val account = state.account
        when {
            !state.loaded -> Text("Loading from ${state.network.label}…", color = LabColors.TextSecondary)
            account == null -> {
                Text("Not activated on ${state.network.label}", color = LabColors.Gold, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val reserve = state.reserves?.baseDrops?.let(Format::xrp) ?: "the base reserve"
                Text(
                    "An address only exists on the ledger once it receives at least $reserve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextSecondary,
                )
            }
            else -> {
                Text(
                    Format.xrp(account.balanceDrops),
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = accent,
                )
                Text(
                    "${Format.xrp(state.availableDrops)} spendable · ${account.ownerCount} owned objects · sequence ${account.sequence}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextSecondary,
                )
            }
        }

        if (state.network.faucetUrl != null) {
            Spacer(Modifier.height(14.dp))
            LabButton(
                if (state.account == null) "Activate with faucet" else "Get more test XRP",
                vm::fund,
                Modifier.fillMaxWidth(),
                accent = accent,
                busy = state.funding,
            )
        }
    }
}

@Composable
private fun SendPanel(state: WalletUiState, accent: Color, vm: WalletViewModel) {
    var destination by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmMainnet by remember { mutableStateOf(false) }

    fun submit() {
        error = vm.send(destination, amount, tag)
    }

    Panel(accent = accent, title = "Send XRP") {
        LabTextField(destination, { destination = it; error = null }, "Destination address", Modifier.fillMaxWidth(), accent, mono = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabTextField(amount, { amount = it; error = null }, "Amount (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
            LabTextField(tag, { tag = it; error = null }, "Tag (optional)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = LabColors.Danger)
        }
        Spacer(Modifier.height(12.dp))
        val working = state.send is SendState.Working
        LabButton(
            "Sign and send",
            { if (state.network == XrplNetwork.MAINNET) confirmMainnet = true else submit() },
            Modifier.fillMaxWidth(),
            accent = accent,
            enabled = destination.isNotBlank() && amount.isNotBlank(),
            busy = working,
        )
        SendStatus(state.send, state.network, onClear = vm::resetSend)
    }

    if (confirmMainnet) {
        AlertDialog(
            onDismissRequest = { confirmMainnet = false },
            title = { Text("Send real XRP?") },
            text = { Text("You're on Mainnet. This payment moves real XRP and can't be reversed.") },
            confirmButton = {
                TextButton(onClick = { confirmMainnet = false; submit() }) { Text("Send", color = LabColors.Gold) }
            },
            dismissButton = { TextButton(onClick = { confirmMainnet = false }) { Text("Cancel") } },
            containerColor = LabColors.SurfaceHigh,
        )
    }
}

@Composable
private fun SendStatus(send: SendState, network: XrplNetwork, onClear: () -> Unit) {
    val uri = LocalUriHandler.current
    when (send) {
        SendState.Idle -> Unit
        is SendState.Working -> {
            Spacer(Modifier.height(10.dp))
            Text(send.step + "…", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
        }
        is SendState.Failed -> {
            Spacer(Modifier.height(10.dp))
            Text(send.message, style = MaterialTheme.typography.bodySmall, color = LabColors.Danger, modifier = Modifier.clickable(onClick = onClear))
        }
        is SendState.Done -> {
            Spacer(Modifier.height(10.dp))
            Text(
                if (send.success) "Validated: ${send.result}" else "Finished with ${send.result}",
                style = MaterialTheme.typography.bodySmall,
                color = if (send.success) LabColors.Matrix else LabColors.Gold,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "View ${send.hash.take(10)}… on the explorer",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.Cyan,
                modifier = Modifier.clickable { uri.openUri("${network.explorerUrl}/transactions/${send.hash}") },
            )
        }
    }
}

@Composable
private fun HistoryPanel(state: WalletUiState, address: String, accent: Color) {
    val uri = LocalUriHandler.current
    Panel(accent = accent, title = "Recent activity", trailing = {
        Text("${state.history.size} txs", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
    }) {
        if (state.history.isEmpty()) {
            Text(
                if (state.refreshing) "Loading…" else "No transactions on ${state.network.label} yet.",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.history.forEach { tx -> HistoryRow(tx, address) { uri.openUri("${state.network.explorerUrl}/transactions/${tx.hash}") } }
        }
    }
}

@Composable
private fun HistoryRow(tx: StreamTx, address: String, onClick: () -> Unit) {
    val incoming = tx.destination == address && tx.account != address
    val color = when {
        !tx.succeeded -> LabColors.Danger
        incoming -> LabColors.Matrix
        else -> LabColors.category(tx.category)
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    tx.type == "Payment" && incoming -> "Received"
                    tx.type == "Payment" -> "Sent"
                    else -> tx.type
                },
                color = LabColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            val counterparty = if (incoming) tx.account else tx.destination
            Text(
                listOfNotNull(counterparty?.let { (if (incoming) "from " else "to ") + Format.shortAddress(it) }, tx.result)
                    .joinToString(" · "),
                fontFamily = Mono,
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
        }
        val amount = Format.amount(tx.amount)
        if (amount.isNotEmpty()) {
            Text(
                (if (incoming) "+" else if (tx.type == "Payment") "−" else "") + amount,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

@Composable
private fun CreateDialog(accent: Color, onDismiss: () -> Unit, onCreate: (String, KeyAlgorithm) -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var algorithm by rememberSaveable { mutableStateOf(KeyAlgorithm.ED25519) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New wallet") },
        text = {
            Column {
                LabTextField(label, { label = it }, "Name (optional)", Modifier.fillMaxWidth(), accent)
                Spacer(Modifier.height(12.dp))
                Text("Key type", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyAlgorithm.entries.forEach { alg ->
                        Chip(alg.label, if (alg == algorithm) accent else LabColors.TextSecondary, alg == algorithm) { algorithm = alg }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ed25519 is faster and the modern default. secp256k1 matches older wallets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(label, algorithm) }) { Text("Create", color = accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = LabColors.SurfaceHigh,
    )
}

@Composable
private fun ImportDialog(accent: Color, onDismiss: () -> Unit, onImport: (String, String) -> String?) {
    var label by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import wallet") },
        text = {
            Column {
                LabTextField(label, { label = it }, "Name (optional)", Modifier.fillMaxWidth(), accent)
                Spacer(Modifier.height(8.dp))
                LabTextField(
                    secret,
                    { secret = it; error = null },
                    "Secret (s…)",
                    Modifier.fillMaxWidth(),
                    accent,
                    mono = true,
                    keyboardType = KeyboardType.Password,
                    isError = error != null,
                    supportingText = error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onImport(label, secret)
                if (error == null) onDismiss()
            }) { Text("Import", color = accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = LabColors.SurfaceHigh,
    )
}

@Composable
private fun RevealDialog(wallet: StoredWallet, secret: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secret for ${wallet.label}") },
        text = {
            Column {
                Text(
                    "Anyone with this secret controls the wallet on every network. Never share a Mainnet secret.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.Gold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    secret,
                    fontFamily = Mono,
                    color = LabColors.TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .padding(12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(secret)) }) { Text("Copy") } },
        containerColor = LabColors.SurfaceHigh,
    )
}
