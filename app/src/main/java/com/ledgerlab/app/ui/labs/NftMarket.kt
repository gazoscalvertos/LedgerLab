package com.ledgerlab.app.ui.labs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabOutlineButton
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.labs.Nft
import com.ledgerlab.xrpl.labs.NftOffer

/** The selected wallet's NFTs, with listing and burning, plus its open offers. */
@Composable
fun CollectionTab(vm: NftViewModel, accent: Color, onOpenMarket: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val mine = state.all.filter { it.owner.id == wallet?.id }.map { it.nft }

    var selling by rememberSaveable { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("10") }
    var buyer by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "${wallet?.label ?: "Your"} NFTs (${mine.size})") {
            ErrorLine(error)
            if (mine.isEmpty()) Text("No NFTs yet. Mint one, or buy one in the Market tab.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                mine.forEach { nft ->
                    Column {
                        NftRow(nft, accent)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabChip("Sell", selling == nft.id, accent) { selling = if (selling == nft.id) null else nft.id }
                            LabChip("Offers", false, LabColors.Cyan) { onOpenMarket(nft.id) }
                            LabChip("Burn", false, LabColors.Danger) { vm.burn(nft) }
                        }
                        if (selling == nft.id) {
                            Spacer(Modifier.height(10.dp))
                            if (!nft.transferable) {
                                Text("This NFT is soulbound: it can only go back to its issuer.", style = MaterialTheme.typography.bodySmall, color = LabColors.Gold)
                                Spacer(Modifier.height(6.dp))
                            }
                            Text("Price in", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                            Spacer(Modifier.height(6.dp))
                            AssetPicker(code, issuer, if (nft.onlyXrp) emptyList() else tokens, accent) { c, i -> code = c; issuer = i }
                            Spacer(Modifier.height(8.dp))
                            LabTextField(price, { price = it }, "Price", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                            Spacer(Modifier.height(8.dp))
                            val others = wallets.filter { it.id != wallet?.id }
                            Text("Only this buyer (optional)", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                            WalletChips(others, others.firstOrNull { it.address == buyer }, LabColors.Cyan, { buyer = if (buyer == it.address) "" else it.address })
                            Spacer(Modifier.height(8.dp))
                            LabTextField(buyer, { buyer = it.trim() }, "Buyer address (blank = anyone)", Modifier.fillMaxWidth(), accent, mono = true)
                            Spacer(Modifier.height(8.dp))
                            LabButton("Create sell offer", { vm.sell(nft, assetOrNull(code, issuer), price, buyer) }, Modifier.fillMaxWidth(), accent)
                        }
                    }
                }
            }
        }
        Panel(accent = accent, title = "Your open NFT offers") {
            if (state.myOffers.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            state.myOffers.forEach { offer ->
                OfferRow(offer, actionLabel = "Cancel", actionColor = LabColors.Danger, onAction = { vm.cancel(offer) }, onTap = { onOpenMarket(offer.nftId) })
            }
        }
    }
}

/** Every offer on one NFT, with buy, accept, bid and cancel. */
@Composable
fun MarketTab(vm: NftViewModel, accent: Color) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val me = wallet?.address

    var typedId by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("5") }
    var ownerInput by rememberSaveable { mutableStateOf("") }

    val nftId = state.marketNftId
    val owned = state.all.firstOrNull { it.nft.id == nftId }
    val owner = owned?.owner?.address ?: state.sellOffers.firstOrNull()?.owner ?: ownerInput

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "Pick an NFT") {
            if (state.all.isNotEmpty()) {
                Text("Held by your wallets", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.all.forEach { o ->
                        LabChip("…${o.nft.id.takeLast(6)} · ${o.owner.label}", o.nft.id == nftId, accent) { vm.chooseMarketNft(o.nft.id) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            LabTextField(typedId, { typedId = it.trim(); vm.chooseMarketNft(it) }, "Or paste any NFT ID", Modifier.fillMaxWidth(), accent, mono = true)
        }

        if (nftId == null) {
            Panel(accent = accent) {
                Text("Pick one of your NFTs, or paste an NFT ID from the explorer, to see its offers.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            }
            return@Column
        }

        owned?.let { o ->
            Panel(accent = accent) {
                NftRow(o.nft, accent)
                Spacer(Modifier.height(4.dp))
                Text("Owned by ${o.owner.label}${if (o.owner.address == me) " (you)" else ""}", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
            }
        }

        Panel(accent = accent, title = "For sale (${state.sellOffers.size})") {
            ErrorLine(error)
            if (state.sellOffers.isEmpty()) Text("No sell offers. The owner can list it in the Collection tab.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            state.sellOffers.forEach { offer ->
                val mineOffer = offer.owner == me
                val blocked = offer.destination != null && offer.destination != me
                OfferRow(
                    offer,
                    actionLabel = when {
                        mineOffer -> "Cancel"
                        blocked -> null
                        else -> "Buy"
                    },
                    actionColor = if (mineOffer) LabColors.Danger else LabColors.Matrix,
                    onAction = { if (mineOffer) vm.cancel(offer) else vm.accept(offer) },
                )
            }
        }

        Panel(accent = accent, title = "Bids (${state.buyOffers.size})") {
            if (state.buyOffers.isEmpty()) Text("No bids yet.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
            state.buyOffers.forEach { offer ->
                val mineOffer = offer.owner == me
                val iOwnIt = owner == me
                OfferRow(
                    offer,
                    actionLabel = when {
                        mineOffer -> "Cancel"
                        iOwnIt -> "Accept"
                        else -> null
                    },
                    actionColor = if (mineOffer) LabColors.Danger else LabColors.Matrix,
                    onAction = { if (mineOffer) vm.cancel(offer) else vm.accept(offer) },
                )
            }
        }

        if (owner != me) {
            Panel(accent = accent, title = "Make a bid") {
                Text("Bid in", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                AssetPicker(code, issuer, if (owned?.nft?.onlyXrp == true) emptyList() else tokens, accent) { c, i -> code = c; issuer = i }
                Spacer(Modifier.height(8.dp))
                LabTextField(price, { price = it }, "Amount", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                if (owned == null && state.sellOffers.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LabTextField(ownerInput, { ownerInput = it.trim() }, "Current owner's address", Modifier.fillMaxWidth(), accent, mono = true)
                }
                Spacer(Modifier.height(8.dp))
                LabButton("Place buy offer", { vm.bid(nftId, owner, assetOrNull(code, issuer), price) }, Modifier.fillMaxWidth(), accent)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bidding in a token? The owner needs a trust line to it to accept, and the NFT must not be XRP-only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.TextMuted,
                )
            }
        }
        LabOutlineButton("Refresh offers", vm::reload, Modifier.fillMaxWidth(), accent)
    }
}

@Composable
private fun NftRow(nft: Nft, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NftImage(nft.uri, Modifier.size(56.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("…" + nft.id.takeLast(16), fontFamily = Mono, color = accent, fontWeight = FontWeight.Bold)
            Text(nft.uri ?: "no URI", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    "taxon ${nft.taxon}",
                    if (nft.transferable) "transferable" else "soulbound",
                    if (nft.transferFee > 0) "${nft.transferFee / 1000.0}% royalty" else null,
                    if (nft.onlyXrp) "XRP only" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextMuted,
            )
        }
    }
}

@Composable
private fun OfferRow(
    offer: NftOffer,
    actionLabel: String?,
    actionColor: Color,
    onAction: () -> Unit,
    onTap: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${if (offer.sell) "Ask" else "Bid"} ${Format.amount(offer.amount)}",
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                color = if (offer.sell) LabColors.Danger else LabColors.Matrix,
            )
            Text(
                "by ${shortAddr(offer.owner)} · NFT …${offer.nftId.takeLast(6)}" + (offer.destination?.let { " · only ${shortAddr(it)}" } ?: ""),
                fontFamily = Mono,
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
        }
        if (actionLabel != null) {
            Text(actionLabel, color = actionColor, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAction).padding(6.dp))
        }
    }
}
