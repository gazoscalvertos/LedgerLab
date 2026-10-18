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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.ledgerlab.xrpl.labs.NameDirectory
import com.ledgerlab.xrpl.labs.NameRecord
import com.ledgerlab.xrpl.labs.NftOffer
import com.ledgerlab.xrpl.labs.XrpNames
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount

data class NameListing(val record: NameRecord, val offer: NftOffer)

data class NamesState(
    val registryAddress: String? = null,
    /** Every top-level name and every subdomain we could find. */
    val all: List<NameRecord> = emptyList(),
    /** Names on sale for anyone (or for the selected wallet), cheapest first. */
    val listings: List<NameListing> = emptyList(),
    /** False when only rippled answered, so owners and subdomains can't be listed. */
    val indexed: Boolean = true,
)

class NamesViewModel(app: Application) : LabViewModel(app) {
    private val _state = MutableStateFlow(NamesState())
    val state: StateFlow<NamesState> = _state.asStateFlow()

    private var registryId: String? = null

    /** The saved wallet playing the registry. In a real service this account would live on a server. */
    fun registry(): StoredWallet? = wallets.value.firstOrNull { it.id == registryId } ?: wallets.value.firstOrNull()

    fun chooseRegistry(id: String) {
        if (id == registryId) return
        registryId = id
        reload()
    }

    private fun directory() = NameDirectory(rpc(), XrplRpc(network.value.clioRpcUrl))

    override suspend fun load() {
        val registry = registry() ?: return
        val dir = directory()
        val q = queries()
        val names = dir.names(registry.address)
        val indexed = names.isEmpty() || names.any { it.owner != null }
        val subs = names.filter { it.owner != null }.groupBy { it.owner!! }.flatMap { (owner, owned) ->
            owned.flatMap { parent -> runCatching { dir.subdomains(parent.name, owner) }.getOrDefault(emptyList()) }
        }
        val all = (names + subs).sortedBy { it.name }
        val me = selected.value?.address
        val listings = all.take(60).flatMap { record ->
            runCatching { q.nftOffers(record.nftId, sell = true) }.getOrDefault(emptyList())
                .filter { it.destination == null || it.destination == me || it.owner == me }
                .map { NameListing(record, it) }
        }.sortedBy { (it.offer.amount as? com.ledgerlab.xrpl.Amount.Xrp)?.drops ?: Long.MAX_VALUE }
        _state.value = NamesState(registry.address, all, listings, indexed)
    }

    /**
     * The whole registration as one flow: the buyer pays the registry 10 XRP (memo names the request),
     * the registry mints the name NFT with a 5% royalty, offers it to the buyer for 0 XRP, and the buyer
     * accepts. Four ledger transactions, all signed on this phone because we hold both keys.
     */
    fun register(input: String, buyer: StoredWallet) {
        val label = "Register name"
        val name = XrpNames.normalize(input)?.takeIf { !XrpNames.isSubdomain(it) }
            ?: return fail(label, "Use letters, digits and hyphens, like alice or my-shop.")
        val registry = registry() ?: return fail(label, "Pick a registry wallet.")
        runWork("Register $name") { step ->
            step("Checking $name is free")
            val dir = directory()
            if (dir.names(registry.address).any { it.name == name }) throw IllegalStateException("$name is already taken.")
            val selfRegistered = buyer.id == registry.id
            if (!selfRegistered) {
                submitAs(buyer, "Paying 10 XRP", step) { a, k, f ->
                    LabTransactions.payment(a, k, f, registry.address, XrpCurrencyAmount.ofDrops(XrpNames.REGISTER_FEE_DROPS), XrpNames.registerMemo(name))
                }
            }
            val mint = submitAs(registry, "Registry mints $name", step) { a, k, f ->
                LabTransactions.nftMint(a, k, f, XrpNames.uriFor(name), XrpNames.NAME_TAXON, transferable = true, transferFee = XrpNames.REGISTRY_ROYALTY)
            }
            if (selfRegistered) return@runWork "$name belongs to the registry wallet."
            val nftId = dir.mintedNftId(mint.hash!!) ?: throw IllegalStateException("Minted, but couldn't read the NFT ID back.")
            val offer = submitAs(registry, "Registry hands it over", step) { a, k, f ->
                LabTransactions.nftCreateOffer(a, k, f, nftId, XrpCurrencyAmount.ofDrops(0), sell = true, destination = buyer.address)
            }
            val offerId = dir.createdOfferId(offer.hash!!) ?: throw IllegalStateException("Couldn't read the transfer offer back.")
            submitAs(buyer, "${buyer.label} accepts", step) { a, k, f -> LabTransactions.nftAcceptOffer(a, k, f, sellOffer = offerId) }
            "${buyer.label} now owns $name."
        }
    }

    /** The owner of `alice.xrp` mints `label.alice.xrp` and puts it up for sale at [price] XRP. */
    fun createSubdomain(parent: NameRecord, labelInput: String, price: String) {
        val title = "Create subdomain"
        val owner = selected.value?.takeIf { it.address == parent.owner } ?: return fail(title, "Switch to the wallet that owns ${parent.name}.")
        val name = XrpNames.normalize("${labelInput.trim().lowercase()}.${parent.name}") ?: return fail(title, "Use letters, digits and hyphens.")
        val drops = if (price.isBlank()) null else Transactions.parseXrp(price) ?: return fail(title, "Enter a price in XRP, or leave it blank.")
        runWork("Create $name") { step ->
            val dir = directory()
            step("Checking $name is free")
            if (dir.subdomains(parent.name, owner.address).any { it.name == name }) throw IllegalStateException("$name already exists.")
            val mint = submitAs(owner, "Minting $name", step) { a, k, f ->
                LabTransactions.nftMint(a, k, f, XrpNames.uriFor(name), XrpNames.SUBDOMAIN_TAXON, transferable = true, transferFee = XrpNames.REGISTRY_ROYALTY)
            }
            if (drops == null) return@runWork "$name minted to ${owner.label}."
            val nftId = dir.mintedNftId(mint.hash!!) ?: throw IllegalStateException("Minted, but couldn't read the NFT ID back.")
            submitAs(owner, "Listing for ${Format.xrp(drops)}", step) { a, k, f ->
                LabTransactions.nftCreateOffer(a, k, f, nftId, XrpCurrencyAmount.ofDrops(drops), sell = true)
            }
            "$name is minted and on sale for ${Format.xrp(drops)}."
        }
    }

    fun list(record: NameRecord, price: String) {
        val drops = Transactions.parseXrp(price) ?: return fail("List ${record.name}", "Enter a price in XRP.")
        runTx("List ${record.name} for ${Format.xrp(drops)}") { a, k, f ->
            LabTransactions.nftCreateOffer(a, k, f, record.nftId, XrpCurrencyAmount.ofDrops(drops), sell = true)
        }
    }

    fun buy(listing: NameListing) = runTx("Buy ${listing.record.name}") { a, k, f ->
        LabTransactions.nftAcceptOffer(a, k, f, sellOffer = listing.offer.id)
    }

    fun cancel(listing: NameListing) = runTx("Stop selling ${listing.record.name}") { a, k, f ->
        LabTransactions.nftCancelOffer(a, k, f, listOf(listing.offer.id))
    }

    /** Resolves the name to its owner's address, then pays them. */
    fun pay(input: String, amount: String) {
        val label = "Send to name"
        val name = XrpNames.normalize(input) ?: return fail(label, "That isn't a valid .xrp name.")
        val drops = Transactions.parseXrp(amount) ?: return fail(label, "Enter an XRP amount.")
        val registry = registry() ?: return fail(label, "Pick a registry wallet.")
        val sender = selected.value ?: return fail(label, "Pick a wallet to pay from.")
        runWork("Send ${Format.xrp(drops)} to $name") { step ->
            step("Resolving $name")
            val record = directory().resolve(name, registry.address) ?: throw IllegalStateException("$name isn't registered.")
            val owner = record.owner ?: throw IllegalStateException("This server can't look up NFT owners. Try Testnet or Devnet.")
            if (owner == sender.address) throw IllegalStateException("$name is yours already.")
            submitAs(sender, "Paying ${Format.shortAddress(owner)}", step) { a, k, f ->
                LabTransactions.payment(a, k, f, owner, XrpCurrencyAmount.ofDrops(drops), "to $name")
            }
            "$name resolved to ${Format.shortAddress(owner)} and the payment validated."
        }
    }
}

private enum class NamesTab(val label: String) { REGISTER("Register"), MINE("My names"), MARKET("Market"), SEND("Send") }

@Composable
fun NamesLab(onBack: () -> Unit, vm: NamesViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Cyan
    var tab by rememberSaveable { mutableStateOf(NamesTab.REGISTER) }

    LabScaffold(".xrp names", "Own a name, sell subdomains, get paid by name", accent, network, vm::selectNetwork, onBack) {
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "One wallet plays the registry and another buys names. Create and fund two (three is more fun) in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent) {
                    Text("Acting as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NamesTab.entries.forEach { t -> LabChip(t.label, tab == t, LabColors.Gold) { tab = t } }
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item {
                ErrorLine(error)
                if (!state.indexed) {
                    Text(
                        "${network.label}'s NFT index (Clio) didn't answer, so owners and subdomains can't be shown. Testnet and Devnet have one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.Gold,
                    )
                }
            }
            when (tab) {
                NamesTab.REGISTER -> item { RegisterTab(vm, state, wallets, wallet, accent) }
                NamesTab.MINE -> item { MyNamesTab(vm, state, wallet, accent) }
                NamesTab.MARKET -> item { NameMarketTab(vm, state, wallet, accent) }
                NamesTab.SEND -> item { SendTab(vm, state, accent) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "A name is an NFT. The registry mints alice.xrp (URI \"xrpns:alice.xrp\", its own taxon) after the buyer pays 10 XRP with a memo naming the request.",
                    "The registry passes it on with a 0 XRP sell offer that only the buyer can accept. Ownership of the NFT is ownership of the name.",
                    "Every name carries a 5% TransferFee, so the registry earns on every resale without any contract code.",
                    "The owner of alice.xrp mints pay.alice.xrp under the subdomain taxon and sells it like any NFT. A subdomain only counts while its issuer still owns the parent.",
                    "Resolving a name is a lookup: Clio's nfts_by_issuer lists the registry's names with their current owners, then you pay that address.",
                    "This is a mockup: a real service would run the registry on a server that watches for payments, and would need rules for renewals and disputes.",
                ),
                code = """
                    {
                      "TransactionType": "NFTokenMint",
                      "Account": "rRegistry…",
                      "NFTokenTaxon": ${XrpNames.NAME_TAXON},
                      "TransferFee": ${XrpNames.REGISTRY_ROYALTY},     // 5% to the registry on resale
                      "Flags": 9,
                      "URI": hex("xrpns:alice.xrp")
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/tokens/nfts · Clio nfts_by_issuer",
            )
        }
    }
}

@Composable
private fun RegisterTab(vm: NamesViewModel, state: NamesState, wallets: List<StoredWallet>, wallet: StoredWallet?, accent: Color) {
    var input by rememberSaveable { mutableStateOf("") }
    val registry = vm.registry()
    val name = XrpNames.normalize(input)?.takeIf { !XrpNames.isSubdomain(it) }
    val taken = name != null && state.all.any { it.name == name }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "Registry") {
            Text(
                "The wallet that runs the name service: it mints names and earns the fees and royalties.",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            WalletChips(wallets, registry, LabColors.Gold, { vm.chooseRegistry(it.id) })
            Spacer(Modifier.height(6.dp))
            KeyValue("Names registered", "${state.all.count { !it.isSubdomain }}")
            KeyValue("Registration fee", Format.xrp(XrpNames.REGISTER_FEE_DROPS))
            KeyValue("Resale royalty", "${XrpNames.REGISTRY_ROYALTY / 1000}%")
        }
        Panel(accent = accent, title = "Claim a name") {
            LabTextField(input, { input = it.trim().lowercase().take(40) }, "Name", Modifier.fillMaxWidth(), accent, mono = true)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    input.isBlank() -> "Letters, digits and hyphens. We'll add .xrp."
                    name == null -> "Not a valid name."
                    taken -> "$name is taken. Check the Market tab in case it's for sale."
                    else -> "$name is available."
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    name != null && !taken -> LabColors.Matrix
                    input.isBlank() -> LabColors.TextMuted
                    else -> LabColors.Danger
                },
            )
            Spacer(Modifier.height(10.dp))
            LabButton(
                "Register ${name ?: "name"} for 10 XRP",
                { wallet?.let { vm.register(input, it) } },
                Modifier.fillMaxWidth(),
                accent,
                enabled = name != null && !taken && wallet != null,
            )
            if (wallet != null && wallet.id == registry?.id) {
                Spacer(Modifier.height(6.dp))
                Text("You're acting as the registry, so the name stays with it. Switch wallet above to buy as a customer.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            }
        }
    }
}

@Composable
private fun MyNamesTab(vm: NamesViewModel, state: NamesState, wallet: StoredWallet?, accent: Color) {
    val mine = state.all.filter { it.owner == wallet?.address }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var sub by rememberSaveable { mutableStateOf("pay") }
    var subPrice by rememberSaveable { mutableStateOf("10") }
    var price by rememberSaveable { mutableStateOf("25") }
    Panel(accent = accent, title = "${wallet?.label ?: "Your"} names (${mine.size})") {
        if (mine.isEmpty()) Text("No names yet. Register one, or buy one in the Market.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            mine.forEach { record ->
                val listing = state.listings.firstOrNull { it.record.nftId == record.nftId && it.offer.owner == wallet?.address }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(record.name, fontFamily = Mono, fontWeight = FontWeight.Bold, color = if (record.isSubdomain) LabColors.Magenta else accent, modifier = Modifier.weight(1f))
                        LabChip(if (expanded == record.nftId) "Close" else "Manage", expanded == record.nftId, accent) {
                            expanded = if (expanded == record.nftId) null else record.nftId
                        }
                    }
                    listing?.let { Text("On sale for ${Format.amount(it.offer.amount)}", style = MaterialTheme.typography.bodySmall, color = LabColors.Gold) }
                    val subs = state.all.count { XrpNames.parentOf(it.name) == record.name }
                    if (!record.isSubdomain && subs > 0) Text("$subs subdomain${if (subs == 1) "" else "s"} issued", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    if (expanded == record.nftId) {
                        Spacer(Modifier.height(10.dp))
                        if (listing != null) {
                            LabOutlineButton("Stop selling", { vm.cancel(listing) }, Modifier.fillMaxWidth(), LabColors.Danger)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LabTextField(price, { price = it }, "Price (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                                LabButton("Sell", { vm.list(record, price) }, Modifier.weight(1f), LabColors.Gold)
                            }
                        }
                        if (!record.isSubdomain) {
                            Spacer(Modifier.height(10.dp))
                            Text("Issue a subdomain", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LabTextField(sub, { sub = it.trim().lowercase().take(32) }, "Label", Modifier.weight(1f), accent, mono = true)
                                LabTextField(subPrice, { subPrice = it }, "Price (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("$sub.${record.name}, listed for sale straight away (leave the price blank to keep it).", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                            Spacer(Modifier.height(6.dp))
                            LabButton("Create $sub.${record.name}", { vm.createSubdomain(record, sub, subPrice) }, Modifier.fillMaxWidth(), LabColors.Magenta)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameMarketTab(vm: NamesViewModel, state: NamesState, wallet: StoredWallet?, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "For sale (${state.listings.size})") {
            if (state.listings.isEmpty()) Text("Nothing listed yet. Owners can sell names and subdomains from My names.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            state.listings.forEach { listing ->
                val mine = listing.offer.owner == wallet?.address
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(listing.record.name, fontFamily = Mono, fontWeight = FontWeight.Bold, color = if (listing.record.isSubdomain) LabColors.Magenta else accent)
                        Text(
                            "${Format.amount(listing.offer.amount)} · from ${shortAddr(listing.offer.owner)}" + (listing.offer.destination?.let { " · reserved for you" } ?: ""),
                            fontFamily = Mono,
                            style = MaterialTheme.typography.bodySmall,
                            color = LabColors.TextSecondary,
                        )
                    }
                    if (mine) {
                        LabOutlineButton("Cancel", { vm.cancel(listing) }, accent = LabColors.Danger)
                    } else {
                        LabButton("Buy", { vm.buy(listing) }, accent = LabColors.Matrix)
                    }
                }
            }
        }
        Panel(accent = accent, title = "Directory") {
            if (state.all.isEmpty()) Text("No names registered with this registry yet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            state.all.forEach { r ->
                KeyValue(r.name, r.owner?.let(::shortAddr) ?: "owner unknown", if (r.isSubdomain) LabColors.Magenta else LabColors.TextPrimary)
            }
        }
        LabOutlineButton("Refresh", vm::reload, Modifier.fillMaxWidth(), accent)
    }
}

@Composable
private fun SendTab(vm: NamesViewModel, state: NamesState, accent: Color) {
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("5") }
    val name = XrpNames.normalize(to)
    val known = state.all.firstOrNull { it.name == name }
    Panel(accent = accent, title = "Pay a name") {
        LabTextField(to, { to = it.trim().lowercase() }, "Name, e.g. alice.xrp", Modifier.fillMaxWidth(), accent, mono = true)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                to.isBlank() -> "Pay anyone by name instead of a 34-character address."
                name == null -> "Not a valid name."
                known?.owner != null -> "$name → ${known.owner}"
                known != null -> "$name exists; the owner is looked up when you send."
                else -> "$name isn't registered with this registry."
            },
            fontFamily = Mono,
            style = MaterialTheme.typography.bodySmall,
            color = if (known != null) LabColors.Matrix else LabColors.TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        LabTextField(amount, { amount = it }, "Amount (XRP)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
        Spacer(Modifier.height(10.dp))
        LabButton("Send", { vm.pay(to, amount) }, Modifier.fillMaxWidth(), accent, enabled = name != null)
    }
}
