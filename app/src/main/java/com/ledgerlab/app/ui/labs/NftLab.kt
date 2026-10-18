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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
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
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.IpfsGateway
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.MetadataStandard
import com.ledgerlab.xrpl.labs.Nft
import com.ledgerlab.xrpl.labs.NftMetadata
import com.ledgerlab.xrpl.labs.NftOffer
import com.ledgerlab.xrpl.labs.PinataClient
import com.ledgerlab.xrpl.wallet.WalletKeys
import kotlinx.coroutines.flow.MutableStateFlow
import org.xrpl.xrpl4j.model.transactions.CurrencyAmount
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

/** A file picked for minting, held in memory until it's pinned. */
class PickedMedia(val name: String, val mimeType: String, val bytes: ByteArray) {
    val sizeLabel: String get() = if (bytes.size >= 1_048_576) "%.1f MB".format(bytes.size / 1_048_576.0) else "${bytes.size / 1024} KB"
}

data class OwnedNft(val owner: StoredWallet, val nft: Nft)

/** What the last mint pinned, so the user can open it on a gateway. */
data class PinnedUpload(val mediaCid: String, val metadataCid: String?)

data class NftState(
    /** NFTs held by every saved wallet, for the collection and market pickers. */
    val all: List<OwnedNft> = emptyList(),
    val myOffers: List<NftOffer> = emptyList(),
    val marketNftId: String? = null,
    val sellOffers: List<NftOffer> = emptyList(),
    val buyOffers: List<NftOffer> = emptyList(),
)

class NftViewModel(app: Application) : LabViewModel(app) {
    override val usesTokens get() = true

    private val _state = MutableStateFlow(NftState())
    val state: StateFlow<NftState> = _state.asStateFlow()

    private val _upload = MutableStateFlow<PinnedUpload?>(null)
    val upload: StateFlow<PinnedUpload?> = _upload.asStateFlow()

    val hasPinataKey: StateFlow<Boolean> = settingsStore.hasPinataKey

    private var marketNftId: String? = null

    override suspend fun load() {
        val wallet = selected.value ?: return
        val q = queries()
        val all = wallets.value.flatMap { w ->
            runCatching { q.nfts(w.address) }.getOrDefault(emptyList()).map { OwnedNft(w, it) }
        }
        val id = marketNftId
        _state.value = NftState(
            all = all,
            myOffers = q.myNftOffers(wallet.address),
            marketNftId = id,
            sellOffers = if (id != null) q.nftOffers(id, sell = true) else emptyList(),
            buyOffers = if (id != null) q.nftOffers(id, sell = false) else emptyList(),
        )
    }

    fun chooseMarketNft(id: String?) {
        val clean = id?.trim()?.uppercase()?.takeIf { it.length == 64 }
        if (clean == marketNftId) return
        marketNftId = clean
        reload()
    }

    fun savePinataKey(jwt: String) = settingsStore.setPinataJwt(jwt)

    fun forgetPinataKey() = settingsStore.setPinataJwt(null)

    fun mint(form: MintForm, media: PickedMedia?) {
        val label = "Mint NFT"
        val taxon = form.taxon.ifBlank { "0" }.toLongOrNull()?.takeIf { it in 0..4_294_967_295L }
            ?: return fail(label, "The taxon is a whole number that groups related NFTs.")
        val percent = form.royalty.ifBlank { "0" }.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal(50) }
            ?: return fail(label, "Royalty is a percentage from 0 to 50.")
        val fee = percent.movePointRight(3).toInt()
        if (fee > 0 && !form.transferable) return fail(label, "A royalty only makes sense on a transferable NFT.")
        if (media == null && form.manualUri.isBlank()) return fail(label, "Pick a file to upload, or paste a URI.")
        if (media == null && form.manualUri.length > 256) return fail(label, "Keep the URI under 256 characters.")
        if (media != null && form.standard != MetadataStandard.MEDIA_ONLY && form.name.isBlank()) return fail(label, "Give the NFT a name.")
        val jwt = if (media != null) settingsStore.pinataJwt() ?: return fail(label, "Add a Pinata API key so the app can upload to IPFS.") else null

        var uri = form.manualUri.trim()
        runTx(
            label,
            before = { step ->
                if (media != null && jwt != null) {
                    val pinata = PinataClient(jwt)
                    step("Uploading ${media.name} (${media.sizeLabel}) to IPFS")
                    val mediaCid = pinata.pinFile(media.name, media.mimeType, media.bytes)
                    val metadata = NftMetadata.build(
                        form.standard, form.name.trim(), form.description.trim(), "ipfs://$mediaCid", media.mimeType,
                        form.collection.trim(), NftMetadata.parseAttributes(form.attributes),
                    )
                    val metaCid = metadata?.let {
                        step("Uploading ${form.standard.label} metadata")
                        pinata.pinJson("${form.name.ifBlank { media.name }}.json", it)
                    }
                    _upload.value = PinnedUpload(mediaCid, metaCid)
                    uri = "ipfs://" + (metaCid ?: mediaCid)
                }
            },
        ) { a, k, f -> LabTransactions.nftMint(a, k, f, uri, taxon, form.transferable, fee, form.onlyXrp) }
    }

    fun burn(nft: Nft) = runTx("Burn NFT ${nft.id.takeLast(6)}") { a, k, f -> LabTransactions.nftBurn(a, k, f, nft.id) }

    /** The owner lists the NFT for [price] of [asset], optionally only for [destination]. */
    fun sell(nft: Nft, asset: Asset?, price: String, destination: String) {
        val label = "List NFT ${nft.id.takeLast(6)}"
        val amount = parsePrice(label, asset, price) ?: return
        if (asset is Asset.Token && nft.onlyXrp) return fail(label, "This NFT was minted to trade for XRP only.")
        if (destination.isNotBlank() && !WalletKeys.isValidAddress(destination)) return fail(label, "The buyer address isn't valid.")
        runTx(label) { a, k, f -> LabTransactions.nftCreateOffer(a, k, f, nft.id, amount, sell = true, destination = destination) }
    }

    /** Anyone but the owner bids [price] of [asset]. The XRP or tokens stay in the bidder's account until accepted. */
    fun bid(nftId: String, owner: String, asset: Asset?, price: String) {
        val label = "Bid on NFT ${nftId.takeLast(6)}"
        val amount = parsePrice(label, asset, price) ?: return
        if (!WalletKeys.isValidAddress(owner)) return fail(label, "Enter the NFT owner's address.")
        if (owner == selected.value?.address) return fail(label, "You already own it. Switch to another wallet to bid.")
        runTx(label) { a, k, f -> LabTransactions.nftCreateOffer(a, k, f, nftId, amount, sell = false, owner = owner) }
    }

    fun accept(offer: NftOffer) = runTx(if (offer.sell) "Buy NFT" else "Accept bid") { a, k, f ->
        if (offer.sell) LabTransactions.nftAcceptOffer(a, k, f, sellOffer = offer.id)
        else LabTransactions.nftAcceptOffer(a, k, f, buyOffer = offer.id)
    }

    fun cancel(offer: NftOffer) = runTx("Cancel NFT offer") { a, k, f -> LabTransactions.nftCancelOffer(a, k, f, listOf(offer.id)) }

    private fun parsePrice(label: String, asset: Asset?, price: String): CurrencyAmount? {
        if (asset == null) {
            fail(label, "Check the token code and issuer.")
            return null
        }
        val value = price.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        if (value == null) {
            fail(label, "Enter a price.")
            return null
        }
        return asset.amount(value)
    }
}

/** Everything typed into the mint form. */
data class MintForm(
    val standard: MetadataStandard,
    val name: String,
    val description: String,
    val collection: String,
    val attributes: String,
    val manualUri: String,
    val taxon: String,
    val transferable: Boolean,
    val royalty: String,
    val onlyXrp: Boolean,
)

private enum class NftTab(val label: String) { MINT("Mint"), COLLECTION("Collection"), MARKET("Market") }

@Composable
fun NftLab(onBack: () -> Unit, vm: NftViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val accent = LabColors.Magenta
    var tab by rememberSaveable { mutableStateOf(NftTab.MINT) }

    LabScaffold("NFTs", "Mint to IPFS, collect and trade", accent, network, vm::selectNetwork, onBack) {
        if (wallets.isEmpty()) {
            item { NeedsWallet(accent) }
        } else {
            item {
                Panel(accent = accent) {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NftTab.entries.forEach { t -> LabChip(t.label, tab == t, LabColors.Cyan) { tab = t } }
                    }
                }
            }
            when (tab) {
                NftTab.MINT -> item { MintTab(vm, accent) }
                NftTab.COLLECTION -> item { CollectionTab(vm, accent) { id -> vm.chooseMarketNft(id); tab = NftTab.MARKET } }
                NftTab.MARKET -> item { MarketTab(vm, accent) }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
        }
        item { NftHowItWorks(tab) }
    }
}

@Composable
private fun MintTab(vm: NftViewModel, accent: Color) {
    val hasKey by vm.hasPinataKey.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var media by remember { mutableStateOf<PickedMedia?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    val pick = rememberMediaPicker(onPicked = { media = it; pickError = null }, onError = { pickError = it })

    var useUri by rememberSaveable { mutableStateOf(false) }
    var standard by rememberSaveable { mutableStateOf(MetadataStandard.XLS24) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var collection by rememberSaveable { mutableStateOf("") }
    var attributes by rememberSaveable { mutableStateOf("") }
    var manualUri by rememberSaveable { mutableStateOf("") }
    var jwt by rememberSaveable { mutableStateOf("") }
    var taxon by rememberSaveable { mutableStateOf("0") }
    var transferable by rememberSaveable { mutableStateOf(true) }
    var royalty by rememberSaveable { mutableStateOf("0") }
    var onlyXrp by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(accent = accent, title = "1 · Media") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabChip("Upload a file", !useUri, accent) { useUri = false }
                LabChip("I have a link", useUri, accent) { useUri = true }
            }
            Spacer(Modifier.height(10.dp))
            if (useUri) {
                LabTextField(manualUri, { manualUri = it }, "URI (ipfs://… or https://…)", Modifier.fillMaxWidth(), accent, mono = true)
            } else {
                val m = media
                if (m != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MediaPreview(m, Modifier.size(84.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(m.name, color = LabColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${m.mimeType} · ${m.sizeLabel}", style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LabOutlineButton(if (m == null) "Choose image, video or audio" else "Choose a different file", pick, Modifier.fillMaxWidth(), accent)
                ErrorLine(pickError)
            }
        }

        if (!useUri) {
            Panel(accent = accent, title = "2 · Metadata standard") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataStandard.entries.forEach { s -> LabChip(s.label, standard == s, accent) { standard = s } }
                }
                Spacer(Modifier.height(8.dp))
                Text(standard.blurb, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary)
                if (standard != MetadataStandard.MEDIA_ONLY) {
                    Spacer(Modifier.height(10.dp))
                    LabTextField(name, { name = it }, "Name", Modifier.fillMaxWidth(), accent)
                    Spacer(Modifier.height(8.dp))
                    LabTextField(description, { description = it }, "Description", Modifier.fillMaxWidth(), accent)
                    if (standard == MetadataStandard.XLS24) {
                        Spacer(Modifier.height(8.dp))
                        LabTextField(collection, { collection = it }, "Collection", Modifier.fillMaxWidth(), accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    LabTextField(attributes, { attributes = it }, "Traits, e.g. eyes=laser, fur=gold", Modifier.fillMaxWidth(), accent)
                }
            }

            Panel(accent = accent, title = "3 · IPFS storage") {
                if (hasKey) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pinata key saved on this phone (encrypted).", color = LabColors.Matrix, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("Remove", color = LabColors.Danger, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.forgetPinataKey() }.padding(6.dp))
                    }
                } else {
                    Text(
                        "Files are pinned to IPFS with Pinata. Create a free API key at app.pinata.cloud (API Keys → New Key), then paste its JWT here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    LabTextField(jwt, { jwt = it.trim() }, "Pinata JWT", Modifier.fillMaxWidth(), accent, mono = true)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabButton("Save key", { vm.savePinataKey(jwt); jwt = "" }, Modifier.weight(1f), accent, enabled = jwt.length > 20)
                        LabOutlineButton("Get a key", { uriHandler.openUri("https://app.pinata.cloud/developers/api-keys") }, Modifier.weight(1f), accent)
                    }
                }
            }
        }

        Panel(accent = accent, title = if (useUri) "2 · Mint" else "4 · Mint") {
            LabTextField(taxon, { taxon = it }, "Taxon (collection number)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(8.dp))
            SwitchRow("Transferable", transferable, accent) { transferable = it }
            if (transferable) {
                LabTextField(royalty, { royalty = it }, "Royalty on resales (%, up to 50)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Decimal)
                Spacer(Modifier.height(4.dp))
            }
            SwitchRow("Only trade for XRP", onlyXrp, accent) { onlyXrp = it }
            Spacer(Modifier.height(8.dp))
            LabButton(
                if (useUri) "Mint NFT" else "Upload to IPFS and mint",
                {
                    vm.mint(
                        MintForm(standard, name, description, collection, attributes, if (useUri) manualUri else "", taxon, transferable, royalty, onlyXrp),
                        if (useUri) null else media,
                    )
                },
                Modifier.fillMaxWidth(),
                accent,
            )
        }

        upload?.let { u ->
            Panel(accent = LabColors.Matrix, title = "Pinned to IPFS") {
                CidLink("Media", u.mediaCid)
                u.metadataCid?.let { CidLink("Metadata", it) }
            }
        }
    }
}

@Composable
private fun CidLink(label: String, cid: String) {
    val uriHandler = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().clickable { IpfsGateway.toHttp(cid)?.let(uriHandler::openUri) }.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary, modifier = Modifier.weight(1f))
        Text("ipfs://${cid.take(10)}…${cid.takeLast(6)}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = LabColors.Cyan)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = accent))
    }
}

@Composable
private fun NftHowItWorks(tab: NftTab) {
    when (tab) {
        NftTab.MINT -> HowItWorks(
            steps = listOf(
                "NFTs are native on the XRPL (XLS-20): no smart contract, just NFTokenMint.",
                "The ledger stores only a URI (up to 256 bytes, as hex). The file itself lives on IPFS, addressed by its content hash (CID).",
                "The app pins your file to IPFS, then (for XLS-24 or OpenSea style) pins a small JSON document pointing at it, and mints with ipfs://<metadata CID>.",
                "XLS-24 is the XRPL community metadata format, read by XRPL wallets and marketplaces. OpenSea style is the ERC-721 shape most multi-chain tools read. Both are just JSON; the ledger doesn't check either.",
                "TransferFee is a royalty the issuer earns on every resale, in 1/1000 of a percent (2500 = 2.5%). Only XRP stops the NFT trading for tokens.",
            ),
            code = """
                {
                  "TransactionType": "NFTokenMint",
                  "NFTokenTaxon": 0,
                  "Flags": 9,              // burnable + transferable
                  "TransferFee": 2500,     // 2.5% royalty
                  "URI": hex("ipfs://bafy…")
                }
            """.trimIndent(),
            docsHint = "Docs: xrpl.org/docs/concepts/tokens/nfts · XLS-24: github.com/XRPLF/XRPL-Standards",
        )
        else -> HowItWorks(
            steps = listOf(
                "Trades are offers on the ledger. A sell offer (flag tfSellNFToken) asks a price; a buy offer bids one and must name the owner.",
                "The price can be XRP or any token, so an NFT can sell for the token you issued in the Tokens lab.",
                "NFTokenAcceptOffer completes the swap in one transaction: the NFT and the payment move atomically.",
                "A sell offer with a Destination can only be accepted by that account, which is how private sales work.",
                "Each open offer is a ledger object costing one owner reserve until it's accepted or cancelled.",
            ),
            code = """
                {
                  "TransactionType": "NFTokenCreateOffer",
                  "NFTokenID": "000800…",
                  "Amount": { "currency": "LAB", "issuer": "rIssuer…", "value": "25" },
                  "Flags": 1               // sell offer
                }
            """.trimIndent(),
            docsHint = "Docs: xrpl.org/docs/concepts/tokens/nfts/trading",
        )
    }
}
