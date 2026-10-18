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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
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
import com.ledgerlab.xrpl.labs.DnsRecord
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.NameDirectory
import com.ledgerlab.xrpl.labs.NameRecord
import com.ledgerlab.xrpl.labs.NameRecords
import com.ledgerlab.xrpl.labs.NameResolver
import com.ledgerlab.xrpl.labs.NftOffer
import com.ledgerlab.xrpl.labs.RecordType
import com.ledgerlab.xrpl.labs.Resolution
import com.ledgerlab.xrpl.labs.XrpNames
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.WalletKeys
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
    /** Records the selected wallet has published for names it owns. */
    val records: List<DnsRecord> = emptyList(),
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
        val mine = all.filter { it.owner == me }.map { it.name }
        val records = if (me == null || mine.isEmpty()) emptyList() else runCatching { resolver().recordsBy(me) }.getOrDefault(emptyList())
            .filter { r -> mine.any { r.name == it || r.name.endsWith(".$it") } }
        _state.value = NamesState(registry.address, all, listings, indexed, records)
    }

    private fun resolver() = NameResolver(rpc(), directory())

    private val _lookup = MutableStateFlow<Resolution?>(null)
    /** The last forward lookup. */
    val lookup: StateFlow<Resolution?> = _lookup.asStateFlow()

    private val _reverse = MutableStateFlow<Pair<String, List<String>>?>(null)
    /** The last reverse lookup: the query and the names that point back at it. */
    val reverse: StateFlow<Pair<String, List<String>>?> = _reverse.asStateFlow()

    /** The owner signs one transaction that publishes (or replaces) a record. */
    fun setRecord(base: NameRecord, host: String, type: RecordType, value: String, ttlText: String) {
        val label = "Publish record"
        val owner = selected.value?.takeIf { it.address == base.owner } ?: return fail(label, "Switch to the wallet that owns ${base.name}.")
        val name = NameRecords.hostName(host, base.name) ?: return fail(label, "Hosts use letters, digits and hyphens, like www or api.v2.")
        type.check(value)?.let { return fail(label, it) }
        val ttl = ttlText.ifBlank { "${NameRecords.DEFAULT_TTL}" }.toLongOrNull()?.takeIf { it in 60..604_800 } ?: return fail(label, "TTL is in seconds, from 60 to 604800.")
        runTx("$name ${type.name} ${value.trim()}", wallet = owner) { a, k, f -> LabTransactions.nameRecord(a, k, f, NameRecords.setMemo(name, type, value, ttl)) }
    }

    fun deleteRecord(record: DnsRecord) {
        val owner = selected.value?.takeIf { it.address == record.signer } ?: return fail("Delete record", "Switch to the wallet that published it.")
        runTx("Delete ${record.name} ${record.type.name}", wallet = owner) { a, k, f ->
            LabTransactions.nameRecord(a, k, f, NameRecords.deleteMemo(record.name, record.type, record.value))
        }
    }

    /** Public forward lookup: anyone can do this, no wallet needed. */
    fun resolveName(query: String) {
        val registry = registry() ?: return fail("Look up", "Pick a registry wallet.")
        if (query.isBlank()) return fail("Look up", "Enter a name, like www.alice.xrp.")
        runWork("Look up ${query.trim()}") { step ->
            step("Finding the owner")
            val r = resolver().resolve(query, registry.address)
            _lookup.value = r
            when {
                r.name == null -> "${r.query} isn't registered."
                r.records.isEmpty() -> "${r.query} has no records yet."
                else -> "${r.records.size} record${if (r.records.size == 1) "" else "s"} for ${r.query}."
            }
        }
    }

    /** Reverse lookup by IP (PTR-style) or by XRPL address. */
    fun reverseLookup(query: String) {
        val registry = registry() ?: return fail("Reverse lookup", "Pick a registry wallet.")
        val q = query.trim()
        val isAddress = WalletKeys.isValidAddress(q)
        if (!isAddress && RecordType.A.check(q) != null && RecordType.AAAA.check(q) != null) return fail("Reverse lookup", "Enter an IP address or an XRPL address.")
        runWork("Reverse lookup $q") { step ->
            step(if (isAddress) "Listing names this account owns" else "Scanning owners' records for $q")
            val names = resolver().reverse(q, registry.address)
            _reverse.value = q to names
            if (names.isEmpty()) "Nothing points at $q." else "${names.size} name${if (names.size == 1) "" else "s"} point at $q."
        }
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

private enum class NamesTab(val label: String) { REGISTER("Register"), MINE("My names"), DNS("DNS"), LOOKUP("Lookup"), MARKET("Market"), SEND("Send") }

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

    LabScaffold(".xrp names", "Own a name, sell subdomains, get paid by name", accent, network, vm::selectNetwork, onBack, features = listOf("NFTs", "Transfer fees", "Memos", "Clio nfts_by_issuer")) {
        item {
            UseCaseHero(
                brand = "XRPNS",
                tagline = "Names, records and a resolver on the ledger",
                icon = Icons.Outlined.Language,
                accent = accent,
                actors = listOf(Actor("Registry", Icons.Outlined.AccountBalance), Actor("Owner", Icons.Outlined.Person), Actor("Visitors", Icons.Outlined.Public)),
                links = listOf("mints the name NFT", "publishes records"),
            )
        }
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "One wallet plays the registry and another buys names. Create and fund two (three is more fun) in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent) {
                    Text("Acting as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                NamesTab.DNS -> item { DnsTab(vm, state, wallet, accent) }
                NamesTab.LOOKUP -> item { LookupTab(vm, accent) }
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
                    "DNS records (A, AAAA, CNAME, TXT) are memos the owner signs on a no-op AccountSet. The resolver finds a name's current owner and replays their record memos in order, so a sale hands the zone to the new owner automatically.",
                    "Reverse lookups scan the owners' records for an IP, the way a PTR record points an address back to a name.",
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

@Composable
private fun DnsTab(vm: NamesViewModel, state: NamesState, wallet: StoredWallet?, accent: Color) {
    val mine = state.all.filter { it.owner == wallet?.address }
    var baseId by rememberSaveable(wallet?.address) { mutableStateOf(mine.firstOrNull()?.nftId) }
    val base = mine.firstOrNull { it.nftId == baseId } ?: mine.firstOrNull()
    var host by rememberSaveable { mutableStateOf("@") }
    var type by rememberSaveable { mutableStateOf(RecordType.A) }
    var value by rememberSaveable { mutableStateOf("") }
    var ttl by rememberSaveable { mutableStateOf("3600") }
    if (base == null) {
        Panel(accent = accent, title = "DNS records") {
            Text("Own a name first. Register one, or buy one in the Market, then publish records for it here.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
        return
    }
    val zone = state.records.filter { it.name == base.name || it.name.endsWith(".${base.name}") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "Zone") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mine.forEach { n -> LabChip(n.name, n.nftId == base.nftId, accent) { baseId = n.nftId } }
            }
            Spacer(Modifier.height(10.dp))
            ZoneFile(base.name, zone, accent, onDelete = vm::deleteRecord)
        }
        Panel(accent = accent, title = "Add a record") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(host, { host = it.trim().lowercase().take(60) }, "Host (@ = the name)", Modifier.weight(1f), accent, mono = true)
                LabTextField(ttl, { ttl = it.filter(Char::isDigit) }, "TTL (s)", Modifier.weight(0.6f), accent, keyboardType = KeyboardType.Number)
            }
            Text(
                (NameRecords.hostName(host, base.name) ?: "not a valid host") + "  →",
                fontFamily = Mono,
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordType.entries.forEach { t -> LabChip(t.name, type == t, LabColors.Gold) { type = t } }
            }
            Spacer(Modifier.height(8.dp))
            LabTextField(value, { value = it.take(255) }, type.hint, Modifier.fillMaxWidth(), accent, mono = true)
            type.check(value)?.takeIf { value.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LabColors.Danger) }
            Spacer(Modifier.height(10.dp))
            LabButton("Sign and publish", { vm.setRecord(base, host, type, value, ttl) }, Modifier.fillMaxWidth(), accent, enabled = type.check(value) == null)
            Text(
                "One signature from your wallet. The record sits in a memo, so it's public and follows the name: sell it and the new owner's records take over.",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
            )
        }
    }
}

/** Records laid out like a zone file. */
@Composable
private fun ZoneFile(origin: String, records: List<DnsRecord>, accent: Color, onDelete: ((DnsRecord) -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LabColors.Background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("\$ORIGIN $origin.", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        if (records.isEmpty()) Text("; no records yet", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        records.forEach { r ->
            val host = if (r.name == origin) "@" else r.name.removeSuffix(".$origin")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary, modifier = Modifier.width(72.dp))
                Text("${r.ttl}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted, modifier = Modifier.width(48.dp))
                Text(r.type.name, fontFamily = Mono, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = LabColors.Gold, modifier = Modifier.width(52.dp))
                Text(if (r.type == RecordType.TXT) "\"${r.value}\"" else r.value, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = accent, modifier = Modifier.weight(1f))
                if (onDelete != null) Text("✕", color = LabColors.Danger, modifier = Modifier.clickable { onDelete(r) }.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun LookupTab(vm: NamesViewModel, accent: Color) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val reverse by vm.reverse.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("alice.xrp") }
    var rquery by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "Resolve a name") {
            Text("The public resolver: anyone can look up a name, no wallet or account needed.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(query, { query = it.trim().lowercase() }, "www.alice.xrp", Modifier.weight(1f), accent, mono = true)
                LabButton("Resolve", { vm.resolveName(query) }, accent = accent)
            }
            lookup?.let { r ->
                Spacer(Modifier.height(10.dp))
                Text(";; ${r.query}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                when (val n = r.name) {
                    null -> Text("NXDOMAIN: not registered", fontFamily = Mono, color = LabColors.Danger)
                    else -> {
                        KeyValue("Zone", n.name)
                        KeyValue("Owner", n.owner?.let(::shortAddr) ?: "unknown")
                        Spacer(Modifier.height(6.dp))
                        ZoneFile(n.name, r.records, accent)
                    }
                }
            }
        }
        Panel(accent = accent, title = "Reverse lookup") {
            Text("Which names point at an IP (like a PTR record), or which names an XRPL address owns.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(rquery, { rquery = it.trim() }, "203.0.113.7 or r…", Modifier.weight(1f), accent, mono = true)
                LabButton("Find", { vm.reverseLookup(rquery) }, accent = accent)
            }
            reverse?.let { (q, names) ->
                Spacer(Modifier.height(10.dp))
                Text(";; $q", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                if (names.isEmpty()) Text("no names", fontFamily = Mono, color = LabColors.TextMuted)
                names.forEach { Text("$q  PTR  $it.", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = accent) }
            }
        }
    }
}
