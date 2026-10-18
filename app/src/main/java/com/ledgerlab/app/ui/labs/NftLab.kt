package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgerlab.app.ui.components.HowItWorks
import com.ledgerlab.app.ui.components.LabButton
import com.ledgerlab.app.ui.components.LabScaffold
import com.ledgerlab.app.ui.components.LabTextField
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.labs.LabTransactions
import com.ledgerlab.xrpl.labs.Nft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NftViewModel(app: Application) : LabViewModel(app) {
    private val _nfts = MutableStateFlow<List<Nft>>(emptyList())
    val nfts: StateFlow<List<Nft>> = _nfts.asStateFlow()

    override suspend fun load() {
        val wallet = selected.value ?: return
        _nfts.value = queries().nfts(wallet.address)
    }

    fun mint(uri: String, taxon: String, transferable: Boolean) {
        val t = taxon.ifBlank { "0" }.toLongOrNull()?.takeIf { it in 0..4_294_967_295L }
            ?: return fail("Mint NFT", "The taxon is a whole number that groups related NFTs.")
        if (uri.length > 256) return fail("Mint NFT", "Keep the URI under 256 characters.")
        runTx("Mint NFT") { a, k, f -> LabTransactions.nftMint(a, k, f, uri, t, transferable) }
    }

    fun burn(nft: Nft) = runTx("Burn NFT ${nft.id.takeLast(6)}") { a, k, f -> LabTransactions.nftBurn(a, k, f, nft.id) }
}

@Composable
fun NftLab(onBack: () -> Unit, vm: NftViewModel = viewModel()) {
    val network by vm.network.collectAsStateWithLifecycle()
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val wallet by vm.selected.collectAsStateWithLifecycle()
    val nfts by vm.nfts.collectAsStateWithLifecycle()
    val tx by vm.tx.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val accent = LabColors.Magenta

    var uri by rememberSaveable { mutableStateOf("ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi") }
    var taxon by rememberSaveable { mutableStateOf("0") }
    var transferable by rememberSaveable { mutableStateOf(true) }

    LabScaffold("NFTs", "Native non-fungible tokens", accent, network, vm::selectNetwork, onBack) {
        if (wallets.isEmpty()) {
            item { NeedsWallet(accent) }
        } else {
            item {
                Panel(accent = accent, title = "Mint") {
                    WalletChips(wallets, wallet, accent, { vm.selectWallet(it.id) })
                    Spacer(Modifier.height(12.dp))
                    LabTextField(uri, { uri = it }, "URI (metadata link)", Modifier.fillMaxWidth(), accent, mono = true)
                    Spacer(Modifier.height(8.dp))
                    LabTextField(taxon, { taxon = it }, "Taxon (collection number)", Modifier.fillMaxWidth(), accent, keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transferable", color = LabColors.TextPrimary, modifier = Modifier.weight(1f))
                        Switch(
                            checked = transferable,
                            onCheckedChange = { transferable = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = accent),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LabButton("Mint NFT", { vm.mint(uri, taxon, transferable) }, Modifier.fillMaxWidth(), accent)
                }
            }
            item { TxStatusCard(tx, network, vm::clearTx) }
            item {
                Panel(accent = accent, title = "${wallet?.label ?: "Your"} NFTs (${nfts.size})") {
                    ErrorLine(error)
                    if (nfts.isEmpty()) Text("No NFTs yet. Mint one above.", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    nfts.forEach { nft ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("…" + nft.id.takeLast(16), fontFamily = Mono, color = accent, fontWeight = FontWeight.Bold)
                                Text(
                                    nft.uri ?: "no URI",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LabColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "taxon ${nft.taxon} · ${if (nft.transferable) "transferable" else "soulbound"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LabColors.TextMuted,
                                )
                            }
                            Text("Burn", color = LabColors.Danger, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.burn(nft) }.padding(6.dp))
                        }
                    }
                }
            }
        }
        item {
            HowItWorks(
                steps = listOf(
                    "NFTs are native on the XRPL (XLS-20): no smart contract, just NFTokenMint.",
                    "Each NFT ID packs its flags, transfer fee, issuer, taxon and a sequence number into 32 bytes.",
                    "The URI is stored as hex and usually points to JSON metadata on IPFS or the web.",
                    "Up to 32 NFTs share one NFTokenPage object, so owning NFTs costs far less reserve than one object each.",
                    "Without the transferable flag the NFT can only go back to its issuer, which makes it soulbound.",
                ),
                code = """
                    {
                      "TransactionType": "NFTokenMint",
                      "Account": "${wallet?.address ?: "rMinter…"}",
                      "NFTokenTaxon": $taxon,
                      "Flags": ${if (transferable) 9 else 1},   // burnable${if (transferable) " + transferable" else ""}
                      "URI": "${LabTransactionsPreview.hex(uri).take(24)}…"
                    }
                """.trimIndent(),
                docsHint = "Docs: xrpl.org/docs/concepts/tokens/nfts",
            )
        }
    }
}

/** Mirrors the core's hex encoding for the explainer preview. */
private object LabTransactionsPreview {
    fun hex(text: String) = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }
}
