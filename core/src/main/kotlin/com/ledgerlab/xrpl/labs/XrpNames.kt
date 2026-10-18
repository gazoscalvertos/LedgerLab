package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import com.ledgerlab.xrpl.wallet.RpcException
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A mock name service: `alice.xrp` is an NFT minted by a registry account, and `pay.alice.xrp` is an NFT
 * minted by whoever owns `alice.xrp`. Owning the NFT is owning the name, so names trade like any NFT.
 */
object XrpNames {
    const val TLD = "xrp"

    /** NFTokenTaxon the registry mints top-level names under. */
    const val NAME_TAXON = 5_897_810L

    /** NFTokenTaxon a name's owner mints its subdomains under. */
    const val SUBDOMAIN_TAXON = 5_897_811L

    /** What registering a top-level name costs, paid to the registry. */
    const val REGISTER_FEE_DROPS = 10_000_000L

    /** The registry's royalty on every resale: 5% (units of 1/1000 of a percent). */
    const val REGISTRY_ROYALTY = 5_000

    private const val URI_PREFIX = "xrpns:"
    private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$")

    /** Lower-cases and adds `.xrp` if missing. Null when it isn't a valid name. */
    fun normalize(input: String): String? {
        val n = input.trim().lowercase().removeSuffix(".").let { if (it.endsWith(".$TLD")) it else "$it.$TLD" }
        val labels = n.removeSuffix(".$TLD").split('.')
        if (labels.size !in 1..2 || labels.any { !label.matches(it) }) return null
        return n
    }

    fun isSubdomain(name: String) = name.removeSuffix(".$TLD").contains('.')

    /** `pay.alice.xrp` -> `alice.xrp`; null for a top-level name. */
    fun parentOf(name: String): String? = if (isSubdomain(name)) name.substringAfter('.') else null

    fun uriFor(name: String) = URI_PREFIX + name

    /** The name an NFT URI (decoded text) stands for, if it's one of ours. */
    fun nameFromUri(uri: String?): String? = uri?.takeIf { it.startsWith(URI_PREFIX) }?.removePrefix(URI_PREFIX)?.let(::normalize)

    fun registerMemo(name: String) = "xrpns:register $name"
}

data class NameRecord(
    val name: String,
    val nftId: String,
    /** Null when the server couldn't say (no Clio index available). */
    val owner: String?,
    val issuer: String,
) {
    val isSubdomain: Boolean get() = XrpNames.isSubdomain(name)
}

/**
 * Reads names from the ledger. Uses Clio's `nfts_by_issuer` index (one call lists every name with its
 * current owner) and falls back to the issuer's transaction history when only rippled is reachable.
 */
class NameDirectory(private val rpc: XrplRpc, private val clio: XrplRpc) {
    /** Every live top-level name minted by [registry]. */
    fun names(registry: String): List<NameRecord> = byIssuer(registry, XrpNames.NAME_TAXON)
        .filter { !it.isSubdomain }

    /** Subdomains of [parent] minted by [parentOwner]. Only those minted by the parent's current owner count. */
    fun subdomains(parent: String, parentOwner: String): List<NameRecord> = byIssuer(parentOwner, XrpNames.SUBDOMAIN_TAXON)
        .filter { XrpNames.parentOf(it.name) == parent }

    /** Finds a name (top-level or subdomain) and its owner. */
    fun resolve(name: String, registry: String): NameRecord? {
        val parent = XrpNames.parentOf(name)
        if (parent == null) return names(registry).firstOrNull { it.name == name }
        val top = names(registry).firstOrNull { it.name == parent } ?: return null
        val owner = top.owner ?: return null
        return subdomains(parent, owner).firstOrNull { it.name == name }
    }

    /** The NFT ID an NFTokenMint created, read from its validated metadata. */
    fun mintedNftId(txHash: String): String? = meta(txHash)?.string("nftoken_id")

    /** The offer ID an NFTokenCreateOffer created. */
    fun createdOfferId(txHash: String): String? = meta(txHash)?.string("offer_id")

    private fun meta(txHash: String): JsonObject? {
        val tx = rpc.call("tx", buildJsonObject { put("transaction", txHash) })
        return tx["meta"] as? JsonObject ?: tx["metaData"] as? JsonObject
    }

    private fun byIssuer(issuer: String, taxon: Long): List<NameRecord> = try {
        clioByIssuer(issuer, taxon)
    } catch (e: Exception) {
        if (e is RpcException && e.error == "actNotFound") emptyList() else historyByIssuer(issuer, taxon)
    }

    private fun clioByIssuer(issuer: String, taxon: Long): List<NameRecord> {
        val out = mutableListOf<NameRecord>()
        var marker: kotlinx.serialization.json.JsonElement? = null
        do {
            val result = clio.call("nfts_by_issuer", buildJsonObject {
                put("issuer", issuer)
                put("nft_taxon", taxon)
                put("limit", 100)
                marker?.let { put("marker", it) }
            })
            (result["nfts"] as? JsonArray).orEmpty().forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                if ((o["is_burned"] as? JsonPrimitive)?.content == "true") return@forEach
                val name = XrpNames.nameFromUri(o.string("uri")?.let(::decodeHex)) ?: return@forEach
                out += NameRecord(name, o.string("nft_id") ?: return@forEach, o.string("owner"), issuer)
            }
            marker = result["marker"]
        } while (marker != null && out.size < 1_000)
        return out.distinctBy { it.name }
    }

    /** Without Clio: names minted in the issuer's recent history. Owners are unknown this way. */
    private fun historyByIssuer(issuer: String, taxon: Long): List<NameRecord> {
        val result = rpc.call("account_tx", buildJsonObject {
            put("account", issuer)
            put("limit", 400)
            put("ledger_index_min", -1)
            put("ledger_index_max", -1)
        })
        return (result["transactions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val entry = e as? JsonObject ?: return@mapNotNull null
            val tx = (entry["tx_json"] ?: entry["tx"]) as? JsonObject ?: return@mapNotNull null
            val meta = (entry["meta"] as? JsonObject) ?: return@mapNotNull null
            if (tx.string("TransactionType") != "NFTokenMint" || tx.long("NFTokenTaxon") != taxon) return@mapNotNull null
            if (meta.string("TransactionResult") != "tesSUCCESS") return@mapNotNull null
            val name = XrpNames.nameFromUri(tx.string("URI")?.let(::decodeHex)) ?: return@mapNotNull null
            NameRecord(name, meta.string("nftoken_id") ?: return@mapNotNull null, null, issuer)
        }.distinctBy { it.name }
    }

    private fun decodeHex(hex: String): String = runCatching {
        String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(hex)
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
