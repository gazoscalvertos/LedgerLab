package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.LedgerCredential
import com.ledgerlab.xrpl.wallet.WalletKeys
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val BADGE_TYPES = listOf("KYC", "Over18", "Member", "Accredited")

fun LedgerCredential.valid(now: Long = rippleNow()): Boolean = accepted && (expiration?.let { it > now } ?: true)

class BadgesViewModel(app: Application) : LabViewModel(app) {
    private val _credentials = MutableStateFlow<List<LedgerCredential>>(emptyList())
    /** Every credential issued by or to a wallet on this phone. */
    val credentials: StateFlow<List<LedgerCredential>> = _credentials.asStateFlow()

    private val _checked = MutableStateFlow<Pair<String, List<LedgerCredential>>?>(null)
    /** The last address looked up in Verify, with its credentials. */
    val checked: StateFlow<Pair<String, List<LedgerCredential>>?> = _checked.asStateFlow()

    private fun appQueries() = AppQueries(rpc(), XrplRpc(network.value.clioRpcUrl))

    override suspend fun load() {
        val q = appQueries()
        _credentials.value = wallets.value.flatMap { w -> runCatching { q.credentials(w.address) }.getOrDefault(emptyList()) }
            .distinctBy { Triple(it.issuer, it.subject, it.type) }
    }

    fun issue(subject: StoredWallet, type: String, days: String, uri: String) {
        val label = "Issue a badge"
        val issuer = selected.value ?: return fail(label, "Pick the issuer's wallet.")
        if (issuer.id == subject.id) return fail(label, "Pick someone else to vouch for.")
        val t = type.trim().takeIf { it.isNotEmpty() && it.length <= 64 } ?: return fail(label, "Give the badge a type, such as KYC.")
        val expiry = if (days.isBlank()) null else days.toLongOrNull()?.takeIf { it in 1..3650 }?.let { rippleNow() + it * 86_400 }
            ?: return fail(label, "Valid for is a number of days, or blank for no expiry.")
        runTx("${issuer.label} vouches: ${subject.label} is $t") { a, k, f -> LabTransactions.credentialCreate(a, k, f, subject.address, t, expiry, uri.trim()) }
    }

    /** The subject agrees to hold the badge. Until then it doesn't count. */
    fun accept(c: LedgerCredential) = runTx("Accept ${c.type} badge") { a, k, f -> LabTransactions.credentialAccept(a, k, f, c.issuer, c.type) }

    /** The issuer revokes, or the subject throws it away. */
    fun delete(c: LedgerCredential) {
        val me = selected.value?.address
        runTx(if (me == c.issuer) "Revoke ${c.type} badge" else "Remove ${c.type} badge") { a, k, f ->
            if (me == c.issuer) LabTransactions.credentialDelete(a, k, f, c.type, subject = c.subject)
            else LabTransactions.credentialDelete(a, k, f, c.type, issuer = c.issuer)
        }
    }

    fun verify(address: String) {
        val label = "Check badges"
        val a = address.trim()
        if (!WalletKeys.isValidAddress(a)) return fail(label, "Enter a valid address.")
        runWork(label) { step ->
            step("Reading ${shortAddr(a)}'s credentials")
            val found = appQueries().credentials(a).filter { it.subject == a }
            _checked.value = a to found
            val good = found.count { it.valid() }
            "${shortAddr(a)} holds $good valid badge${if (good == 1) "" else "s"}."
        }
    }
}

private enum class BadgesTab(val label: String) { ISSUE("Issue"), MINE("My badges"), VERIFY("Verify") }

@Composable
fun BadgesLab(onBack: () -> Unit, vm: BadgesViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val credentials by vm.credentials.collectAsStateWithLifecycle()
    val checked by vm.checked.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Matrix
    var tab by rememberSaveable { mutableStateOf(BadgesTab.ISSUE) }

    LabScaffold("Verified badges", "On-ledger credentials: KYC, age, membership", accent, network, vm::selectNetwork, onBack, features = listOf("Credentials (XLS-70)", "CredentialAccept", "CredentialDelete")) {
        item {
            UseCaseHero(
                brand = "TrustBadge",
                tagline = "Verified credentials anyone can check",
                icon = Icons.Outlined.WorkspacePremium,
                accent = accent,
                actors = listOf(Actor("Issuer", Icons.Outlined.VerifiedUser), Actor("Holder", Icons.Outlined.Person), Actor("Verifier", Icons.Outlined.Search)),
                links = listOf("issues a credential", "reads the ledger"),
            )
        }
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "One wallet issues badges and another holds them. Create and fund two in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent) {
                    Text("Acting as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BadgesTab.entries.forEach { t -> LabChip(t.label, tab == t, LabColors.Gold) { tab = t } }
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item { ErrorLine(error) }
            when (tab) {
                BadgesTab.ISSUE -> item { IssuePanel(vm, credentials, wallet, wallets, accent) }
                BadgesTab.MINE -> item { MyBadgesPanel(vm, credentials, wallet, wallets, accent) }
                BadgesTab.VERIFY -> item { VerifyPanel(vm, checked, wallets, accent) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "A credential is a ledger object saying \"issuer vouches that subject is type\", such as a KYC provider vouching for a customer. It can expire and link to off-ledger details with a URI.",
                    "The subject must accept it before it counts, so nobody can be labelled without agreeing.",
                    "Anyone can check a credential by reading the ledger: no database, no API key, and only the type is public, not the documents behind it.",
                    "The issuer can revoke it at any time and the subject can remove it, both with CredentialDelete.",
                    "Credentials plug into permissioned features: an account with Deposit Authorization can accept payments only from holders of a given badge (DepositPreauth with AuthorizeCredentials).",
                ),
                code = """
                    { "TransactionType": "CredentialCreate", "Account": "rIssuer…",
                      "Subject": "rCustomer…", "CredentialType": hex("KYC"),
                      "Expiration": <optional>, "URI": hex("https://…") }
                    { "TransactionType": "CredentialAccept", "Account": "rCustomer…",
                      "Issuer": "rIssuer…", "CredentialType": hex("KYC") }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/decentralized-storage/credentials",
            )
        }
    }
}

@Composable
private fun IssuePanel(vm: BadgesViewModel, credentials: List<LedgerCredential>, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color) {
    val others = wallets.filter { it.id != wallet?.id }
    var subjectId by rememberSaveable(wallet?.id) { mutableStateOf(others.firstOrNull()?.id) }
    var type by rememberSaveable { mutableStateOf(BADGE_TYPES.first()) }
    var days by rememberSaveable { mutableStateOf("365") }
    var uri by rememberSaveable { mutableStateOf("") }
    val issued = credentials.filter { it.issuer == wallet?.address }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "Vouch as ${wallet?.label ?: "issuer"}") {
            Text("For", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            WalletChips(others, others.firstOrNull { it.id == subjectId }, LabColors.Cyan, { subjectId = it.id })
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BADGE_TYPES.forEach { t -> LabChip(t, type == t, accent) { type = t } }
            }
            Spacer(Modifier.height(8.dp))
            LabTextField(type, { type = it.take(64) }, "Badge type", Modifier.fillMaxWidth(), accent, mono = true)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabTextField(days, { days = it.filter(Char::isDigit) }, "Valid for (days)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
                LabTextField(uri, { uri = it.take(200) }, "Details URI (optional)", Modifier.weight(1f), accent)
            }
            Spacer(Modifier.height(10.dp))
            LabButton("Issue badge", { others.firstOrNull { it.id == subjectId }?.let { vm.issue(it, type, days, uri) } }, Modifier.fillMaxWidth(), accent, enabled = subjectId != null)
        }
        Panel(accent = accent, title = "Issued by ${wallet?.label ?: "you"} (${issued.size})") {
            if (issued.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            issued.forEach { c -> BadgeRow(c, wallets, "to ${nameOf(c.subject, wallets)}") { LabOutlineButton("Revoke", { vm.delete(c) }, accent = LabColors.Danger) } }
        }
    }
}

@Composable
private fun MyBadgesPanel(vm: BadgesViewModel, credentials: List<LedgerCredential>, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color) {
    val mine = credentials.filter { it.subject == wallet?.address }
    Panel(accent = accent, title = "${wallet?.label ?: "Your"} badges (${mine.size})") {
        if (mine.isEmpty()) Text("No badges yet. Have another wallet issue one.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        mine.forEach { c ->
            BadgeRow(c, wallets, "from ${nameOf(c.issuer, wallets)}") {
                if (!c.accepted) LabButton("Accept", { vm.accept(c) }, accent = LabColors.Matrix)
                else LabOutlineButton("Remove", { vm.delete(c) }, accent = LabColors.TextMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        LabOutlineButton("Refresh", vm::reload, Modifier.fillMaxWidth(), accent)
    }
}

@Composable
private fun VerifyPanel(vm: BadgesViewModel, checked: Pair<String, List<LedgerCredential>>?, wallets: List<StoredWallet>, accent: Color) {
    var address by rememberSaveable { mutableStateOf(wallets.getOrNull(1)?.address ?: "") }
    Panel(accent = accent, title = "Check anyone's badges") {
        WalletChips(wallets, wallets.firstOrNull { it.address == address }, LabColors.Cyan, { address = it.address })
        Spacer(Modifier.height(6.dp))
        LabTextField(address, { address = it.trim() }, "Address", Modifier.fillMaxWidth(), accent, mono = true)
        Spacer(Modifier.height(8.dp))
        LabButton("Check the ledger", { vm.verify(address) }, Modifier.fillMaxWidth(), accent)
        checked?.let { (who, found) ->
            Spacer(Modifier.height(10.dp))
            Text(nameOf(who, wallets), fontWeight = FontWeight.SemiBold, color = LabColors.TextPrimary)
            if (found.isEmpty()) Text("No credentials.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            found.forEach { c -> BadgeRow(c, wallets, "vouched by ${nameOf(c.issuer, wallets)}") {} }
        }
    }
}

@Composable
private fun BadgeRow(c: LedgerCredential, wallets: List<StoredWallet>, who: String, action: @Composable () -> Unit) {
    val valid = c.valid()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (valid) Brush.linearGradient(listOf(LabColors.Matrix, LabColors.Cyan)) else Brush.linearGradient(listOf(LabColors.Outline, LabColors.Surface))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (valid) Icons.Outlined.VerifiedUser else Icons.Outlined.HourglassEmpty, contentDescription = null, tint = LabColors.Background)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.type, fontFamily = Mono, fontWeight = FontWeight.Bold, color = if (valid) LabColors.Matrix else LabColors.TextMuted)
            Text(
                who + " · " + when {
                    !c.accepted -> "not accepted yet"
                    !valid -> "expired"
                    c.expiration != null -> "valid until ${formatLedgerTime(c.expiration ?: 0)}"
                    else -> "valid, no expiry"
                },
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            c.uri?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LabColors.Cyan) }
        }
        action()
    }
}
