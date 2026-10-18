package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Storefront
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
import androidx.compose.ui.geometry.Offset
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
import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.AppQueries
import com.ledgerlab.xrpl.labs.IssuedNft
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.NftOffer
import com.ledgerlab.xrpl.labs.Tickets
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.XrplRpc
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount

data class Ticket(val number: Int, val nft: IssuedNft, val offer: NftOffer?) {
    val admitted: Boolean get() = nft.burned
}

data class TicketsState(
    val organiser: String? = null,
    val event: String = "",
    val tickets: List<Ticket> = emptyList(),
    /** False when the NFT index (Clio) couldn't be reached, so tickets can't be listed. */
    val indexed: Boolean = true,
)

class TicketsViewModel(app: Application) : LabViewModel(app) {
    private val _state = MutableStateFlow(TicketsState())
    val state: StateFlow<TicketsState> = _state.asStateFlow()

    private var organiserId: String? = null
    private var event = "Neon Nights 2026"

    /** The saved wallet playing the event organiser. */
    fun organiser(): StoredWallet? = wallets.value.firstOrNull { it.id == organiserId } ?: wallets.value.firstOrNull()

    fun chooseOrganiser(id: String) {
        if (id == organiserId) return
        organiserId = id
        reload()
    }

    fun chooseEvent(name: String) {
        if (name.isBlank() || name.trim() == event) return
        event = name.trim()
        reload()
    }

    private fun appQueries() = AppQueries(rpc(), XrplRpc(network.value.clioRpcUrl))

    override suspend fun load() {
        val organiser = organiser() ?: return
        val issued = try {
            appQueries().nftsByIssuer(organiser.address, Tickets.taxonFor(event))
        } catch (e: Exception) {
            _state.value = TicketsState(organiser.address, event, emptyList(), indexed = false)
            return
        }
        val q = queries()
        val tickets = issued.mapNotNull { nft ->
            val (name, number) = Tickets.parse(nft.uri) ?: return@mapNotNull null
            if (!name.equals(event, ignoreCase = true)) return@mapNotNull null
            val offer = if (nft.burned) null else runCatching { q.nftOffers(nft.id, sell = true) }.getOrDefault(emptyList())
                .filter { it.owner == nft.owner && it.destination == null }
                .minByOrNull { (it.amount as? Amount.Xrp)?.drops ?: Long.MAX_VALUE }
            Ticket(number, nft, offer)
        }.sortedBy { it.number }
        _state.value = TicketsState(organiser.address, event, tickets)
    }

    /**
     * Mints [countText] tickets and lists each at face value. With [resale] they're transferable and
     * carry a royalty for the organiser; without, the ledger itself refuses any transfer that doesn't
     * involve the organiser, so a ticket can only be bought from (or handed back to) them.
     */
    fun mint(countText: String, priceText: String, resale: Boolean, royaltyText: String) {
        val label = "Mint tickets"
        val organiser = organiser() ?: return fail(label, "Pick an organiser wallet.")
        val count = countText.toIntOrNull()?.takeIf { it in 1..10 } ?: return fail(label, "Mint between 1 and 10 tickets at a time.")
        val drops = Transactions.parseXrp(priceText) ?: return fail(label, "Enter a face value in XRP.")
        val royalty = if (!resale) 0 else royaltyText.ifBlank { "0" }.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal(50) }?.movePointRight(3)?.toInt()
            ?: return fail(label, "The royalty is a percentage from 0 to 50.")
        val first = (_state.value.tickets.maxOfOrNull { it.number } ?: 0) + 1
        val eventName = event
        runWork("Mint $count tickets for $eventName") { step ->
            val q = appQueries()
            (first until first + count).forEach { n ->
                val mint = submitAs(organiser, "Ticket #$n: minting", step) { a, k, f ->
                    LabTransactions.nftMint(a, k, f, Tickets.uriFor(eventName, n), Tickets.taxonFor(eventName), transferable = resale, transferFee = royalty)
                }
                val nftId = q.createdId(mint.hash!!, "nftoken_id") ?: throw IllegalStateException("Ticket #$n minted, but its NFT ID couldn't be read back.")
                submitAs(organiser, "Ticket #$n: listing at ${Format.xrp(drops)}", step) { a, k, f ->
                    LabTransactions.nftCreateOffer(a, k, f, nftId, XrpCurrencyAmount.ofDrops(drops), sell = true)
                }
            }
            "Tickets #$first to #${first + count - 1} are on sale at ${Format.xrp(drops)} each."
        }
    }

    fun buy(ticket: Ticket) {
        val offer = ticket.offer ?: return fail("Buy ticket", "That ticket isn't for sale.")
        if (selected.value?.address == offer.owner) return fail("Buy ticket", "That's your own listing. Switch wallet to buy as a fan.")
        runTx("Buy ticket #${ticket.number}") { a, k, f -> LabTransactions.nftAcceptOffer(a, k, f, sellOffer = offer.id) }
    }

    /** The holder lists their ticket. On a no-resale ticket the ledger rejects this, which is the point. */
    fun resell(ticket: Ticket, priceText: String) {
        val drops = Transactions.parseXrp(priceText) ?: return fail("Resell ticket", "Enter a price in XRP.")
        runTx("Resell ticket #${ticket.number} for ${Format.xrp(drops)}") { a, k, f ->
            LabTransactions.nftCreateOffer(a, k, f, ticket.nft.id, XrpCurrencyAmount.ofDrops(drops), sell = true)
        }
    }

    fun cancelListing(ticket: Ticket) {
        val offer = ticket.offer ?: return
        runTx("Stop selling ticket #${ticket.number}") { a, k, f -> LabTransactions.nftCancelOffer(a, k, f, listOf(offer.id)) }
    }

    /** At the door: the organiser burns the ticket from the holder's wallet. It can't be used twice. */
    fun checkIn(ticket: Ticket) {
        val organiser = organiser() ?: return fail("Check in", "Pick an organiser wallet.")
        val owner = ticket.nft.owner ?: return fail("Check in", "Couldn't tell who holds this ticket.")
        if (owner == organiser.address) return fail("Check in", "Nobody has bought ticket #${ticket.number} yet.")
        runTx("Check in ticket #${ticket.number}", wallet = organiser) { a, k, f ->
            LabTransactions.nftBurn(a, k, f, ticket.nft.id, owner = owner)
        }
    }
}

private enum class TicketsTab(val label: String) { ORGANISE("Organise"), SHOP("Box office"), MINE("My tickets"), DOOR("Door") }

@Composable
fun TicketsLab(onBack: () -> Unit, vm: TicketsViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Magenta
    var tab by rememberSaveable { mutableStateOf(TicketsTab.ORGANISE) }

    LabScaffold("Event tickets", "NFT tickets with royalties and door check-in", accent, network, vm::selectNetwork, onBack, features = listOf("NFTs", "NFT offers", "Transfer fees", "NFTokenBurn", "Clio")) {
        item {
            UseCaseHero(
                brand = "TixChain",
                tagline = "Scalp-proof tickets in your wallet",
                icon = Icons.Outlined.ConfirmationNumber,
                accent = accent,
                actors = listOf(Actor("Organiser", Icons.Outlined.Storefront), Actor("Fan", Icons.Outlined.Person), Actor("Door", Icons.Outlined.QrCodeScanner)),
                links = listOf("sells an NFT ticket", "burns it at entry"),
            )
        }
        if (wallets.size < 2) {
            item { NeedsWallet(accent, "One wallet is the organiser and another is a fan. Create and fund two (three lets you try resale) in the Wallet lab.") }
        } else {
            item {
                Panel(accent = accent) {
                    Text("Acting as", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TicketsTab.entries.forEach { t -> LabChip(t.label, tab == t, LabColors.Gold) { tab = t } }
                    }
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item {
                ErrorLine(error)
                if (!state.indexed) {
                    Text(
                        "${network.label}'s NFT index (Clio) didn't answer, so tickets can't be listed. Testnet and Devnet have one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.Gold,
                    )
                }
            }
            when (tab) {
                TicketsTab.ORGANISE -> item { OrganiseTab(vm, state, wallets, accent) }
                TicketsTab.SHOP -> item { BoxOfficeTab(vm, state, wallet, wallets, accent) }
                TicketsTab.MINE -> item { MyTicketsTab(vm, state, wallet, accent) }
                TicketsTab.DOOR -> item { DoorTab(vm, state, wallet, wallets, accent) }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "Each ticket is an NFT the organiser mints, grouped under a taxon derived from the event name, with URI \"xrptix:<event>#<number>\".",
                    "Open resale: the ticket is transferable and carries a TransferFee, so the organiser earns a royalty on every resale, enforced by the ledger.",
                    "No resale: the ticket isn't transferable. The ledger then refuses any trade that doesn't involve the organiser, so fans can't scalp it (try it in My tickets).",
                    "The ledger can't cap a resale price: a royalty makes scalping less attractive, and a real app would sell only through brokered offers it approves.",
                    "At the door the organiser burns the ticket straight from the fan's wallet (tickets are minted burnable), so it can never be used twice. Burned tickets show as admitted.",
                ),
                code = """
                    {
                      "TransactionType": "NFTokenBurn",
                      "Account": "rOrganiser…",
                      "NFTokenID": "000B…",
                      "Owner": "rFan…"          // issuer burning a holder's ticket
                    }
                """.trimIndent(),
                accent = accent,
                docsHint = "Docs: xrpl.org/docs/concepts/tokens/nfts · NFTokenBurn",
            )
        }
    }
}

@Composable
private fun OrganiseTab(vm: TicketsViewModel, state: TicketsState, wallets: List<StoredWallet>, accent: Color) {
    var event by rememberSaveable { mutableStateOf(state.event.ifBlank { "Neon Nights 2026" }) }
    var count by rememberSaveable { mutableStateOf("3") }
    var price by rememberSaveable { mutableStateOf("5") }
    var resale by rememberSaveable { mutableStateOf(true) }
    var royalty by rememberSaveable { mutableStateOf("10") }
    val organiser = vm.organiser()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "Event") {
            Text("Organiser", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
            WalletChips(wallets, organiser, LabColors.Gold, { vm.chooseOrganiser(it.id) })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabTextField(event, { event = it.take(60) }, "Event name", Modifier.weight(1f), accent)
                LabOutlineButton("Open", { vm.chooseEvent(event) }, accent = accent)
            }
            Spacer(Modifier.height(6.dp))
            KeyValue("Showing", state.event)
            KeyValue("Taxon", "${Tickets.taxonFor(state.event.ifBlank { event })}")
            KeyValue("Tickets issued", "${state.tickets.size}")
            KeyValue("Sold", "${state.tickets.count { it.nft.owner != state.organiser }}")
            KeyValue("Admitted", "${state.tickets.count { it.admitted }}")
        }
        Panel(accent = accent, title = "Mint tickets") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabTextField(count, { count = it.filter(Char::isDigit).take(2) }, "How many (1-10)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Number)
                LabTextField(price, { price = it }, "Face value (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabChip("Open resale", resale, accent) { resale = true }
                LabChip("No resale", !resale, accent) { resale = false }
            }
            if (resale) {
                Spacer(Modifier.height(8.dp))
                LabTextField(royalty, { royalty = it }, "Organiser royalty on resale (%)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (resale) "Fans can resell; the ledger pays you the royalty every time." else "Fans can't pass tickets on. The ledger enforces it.",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            LabButton("Mint and list for \"${state.event}\"", { vm.mint(count, price, resale, royalty) }, Modifier.fillMaxWidth(), accent)
            Text("Two transactions per ticket, signed as the organiser.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
    }
}

@Composable
private fun BoxOfficeTab(vm: TicketsViewModel, state: TicketsState, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color) {
    val forSale = state.tickets.filter { it.offer != null }
    Panel(accent = accent, title = "${state.event}: ${forSale.size} for sale") {
        if (forSale.isEmpty()) Text("Nothing on sale. The organiser mints tickets in Organise.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            forSale.forEach { t ->
                val offer = t.offer!!
                val mine = offer.owner == wallet?.address
                TicketStub(
                    state.event,
                    t.number,
                    Format.amount(offer.amount),
                    if (offer.owner == state.organiser) "Box office" else "Resale · ${nameOf(offer.owner, wallets)}",
                    accent,
                ) {
                    if (mine) LabOutlineButton("Cancel", { vm.cancelListing(t) }, accent = LabColors.Danger)
                    else LabButton("Buy", { vm.buy(t) }, accent = LabColors.Matrix)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LabOutlineButton("Refresh", vm::reload, Modifier.fillMaxWidth(), accent)
    }
}

@Composable
private fun MyTicketsTab(vm: TicketsViewModel, state: TicketsState, wallet: StoredWallet?, accent: Color) {
    val mine = state.tickets.filter { it.nft.owner == wallet?.address && !it.admitted && wallet?.address != state.organiser }
    var price by rememberSaveable { mutableStateOf("8") }
    Panel(accent = accent, title = "${wallet?.label ?: "Your"} tickets (${mine.size})") {
        if (wallet?.address == state.organiser) {
            Text("You're the organiser. Switch to a fan's wallet to see their tickets.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        } else if (mine.isEmpty()) {
            Text("No tickets yet. Buy one at the Box office.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            mine.forEach { t ->
                Column {
                    TicketStub(state.event, t.number, "ADMIT ONE", if (t.nft.transferable) "Resale allowed · royalty to organiser" else "No resale", accent) {}
                    Spacer(Modifier.height(6.dp))
                    if (t.offer != null) {
                        Text("Listed for ${Format.amount(t.offer.amount)}", style = MaterialTheme.typography.bodySmall, color = LabColors.Gold)
                        LabOutlineButton("Stop selling", { vm.cancelListing(t) }, Modifier.fillMaxWidth(), LabColors.Danger)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            LabTextField(price, { price = it }, "Price (XRP)", Modifier.weight(1f), accent, keyboardType = KeyboardType.Decimal)
                            LabButton(if (t.nft.transferable) "Resell" else "Try to resell", { vm.resell(t, price) }, Modifier.weight(1f), LabColors.Gold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoorTab(vm: TicketsViewModel, state: TicketsState, wallet: StoredWallet?, wallets: List<StoredWallet>, accent: Color) {
    val sold = state.tickets.filter { it.nft.owner != state.organiser || it.admitted }
    Panel(accent = accent, title = "Door: ${state.event}") {
        Text(
            "Check-in is signed by the organiser (${nameOf(state.organiser, wallets)}), whichever wallet you're acting as.",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        if (sold.isEmpty()) Text("No tickets sold yet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        sold.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ticket #${t.number}", fontWeight = FontWeight.Bold, color = if (t.admitted) LabColors.TextMuted else accent)
                    Text(
                        if (t.admitted) "Admitted · burned" else "Held by ${nameOf(t.nft.owner, wallets)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.admitted) LabColors.Matrix else LabColors.TextSecondary,
                    )
                }
                if (!t.admitted) LabButton("Check in", { vm.checkIn(t) }, accent = LabColors.Matrix)
            }
        }
        Spacer(Modifier.height(8.dp))
        LabOutlineButton("Refresh", vm::reload, Modifier.fillMaxWidth(), accent)
    }
}

/** A ticket drawn as a stub: event and seat on the left, a perforated edge, the number on the right. */
@Composable
private fun TicketStub(event: String, number: Int, price: String, detail: String, accent: Color, action: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.28f), LabColors.SurfaceHigh)))
            .border(1.dp, accent.copy(alpha = 0.5f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(14.dp)) {
            Text(event.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
            Text(price, fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            action()
        }
        Canvas(Modifier.width(2.dp).height(86.dp)) {
            var y = 0f
            while (y < size.height) {
                drawLine(accent.copy(alpha = 0.6f), Offset(size.width / 2, y), Offset(size.width / 2, y + 5f), strokeWidth = size.width)
                y += 11f
            }
        }
        Column(Modifier.width(64.dp).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ConfirmationNumber, contentDescription = null, tint = accent)
            Text("#$number", fontFamily = Mono, fontWeight = FontWeight.Bold, color = LabColors.TextPrimary)
        }
    }
}
