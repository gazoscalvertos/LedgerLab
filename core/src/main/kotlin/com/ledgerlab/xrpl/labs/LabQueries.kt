package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.Amount
import com.ledgerlab.xrpl.StreamParser
import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import com.ledgerlab.xrpl.wallet.RpcException
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class TrustLine(val currency: String, val issuer: String, val balance: String, val limit: String)

data class AccountOffer(val sequence: Long, val gets: Amount?, val pays: Amount?)

data class BookOffer(val account: String, val gets: Amount?, val pays: Amount?, val quality: Double?)

data class Nft(val id: String, val uri: String?, val taxon: Long, val issuer: String, val transferable: Boolean)

data class EscrowEntry(
    val owner: String,
    val destination: String,
    val drops: Long,
    /** Seconds since the Ripple epoch. */
    val finishAfter: Long?,
    val cancelAfter: Long?,
    /** The Sequence of the EscrowCreate, which EscrowFinish refers to. Null if it couldn't be found. */
    val createSequence: Long?,
)

data class AmmPool(
    val account: String,
    val amount1: Amount?,
    val amount2: Amount?,
    val lpToken: Amount?,
    /** In units of 1/100,000. */
    val tradingFee: Int,
)

/** Read-only queries the labs use. All blocking. */
class LabQueries(private val rpc: XrplRpc) {
    fun trustLines(address: String): List<TrustLine> = list("account_lines", "lines", address) { o ->
        TrustLine(
            currency = StreamParser.decodeCurrency(o.string("currency") ?: return@list null),
            issuer = o.string("account") ?: return@list null,
            balance = o.string("balance") ?: "0",
            limit = o.string("limit") ?: "0",
        )
    }

    fun offers(address: String): List<AccountOffer> = list("account_offers", "offers", address) { o ->
        AccountOffer(
            sequence = o.long("seq") ?: return@list null,
            gets = StreamParser.parseAmount(o["taker_gets"]),
            pays = StreamParser.parseAmount(o["taker_pays"]),
        )
    }

    /** Offers that give [gets] in exchange for [pays], best first. */
    fun book(gets: Asset, pays: Asset, limit: Int = 8): List<BookOffer> {
        val result = rpc.call("book_offers", buildJsonObject {
            putAsset("taker_gets", gets)
            putAsset("taker_pays", pays)
            put("limit", limit)
            put("ledger_index", "validated")
        })
        return (result["offers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BookOffer(
                account = o.string("Account") ?: "",
                gets = StreamParser.parseAmount(o["TakerGets"]),
                pays = StreamParser.parseAmount(o["TakerPays"]),
                quality = o.string("quality")?.toDoubleOrNull(),
            )
        }
    }

    fun nfts(address: String): List<Nft> = list("account_nfts", "account_nfts", address) { o ->
        val flags = o.long("Flags") ?: 0L
        Nft(
            id = o.string("NFTokenID") ?: return@list null,
            uri = o.string("URI")?.let(::decodeHex),
            taxon = o.long("NFTokenTaxon") ?: 0L,
            issuer = o.string("Issuer") ?: "",
            transferable = flags and 0x0008L != 0L,
        )
    }

    fun escrows(address: String): List<EscrowEntry> {
        val raw = list("account_objects", "account_objects", address, { put("type", "escrow") }) { it }
        return raw.map { o ->
            EscrowEntry(
                owner = o.string("Account") ?: address,
                destination = o.string("Destination") ?: "",
                drops = o.long("Amount") ?: 0L,
                finishAfter = o.long("FinishAfter"),
                cancelAfter = o.long("CancelAfter"),
                createSequence = o.string("PreviousTxnID")?.let(::sequenceOf),
            )
        }
    }

    /** Null when no pool exists for the pair. */
    fun amm(asset1: Asset, asset2: Asset): AmmPool? {
        val result = try {
            rpc.call("amm_info", buildJsonObject {
                putAsset("asset", asset1)
                putAsset("asset2", asset2)
                put("ledger_index", "validated")
            })
        } catch (e: RpcException) {
            if (e.error == "actNotFound" || e.error == "objectNotFound") return null
            throw e
        }
        val amm = result["amm"] as? JsonObject ?: return null
        return AmmPool(
            account = amm.string("account") ?: "",
            amount1 = StreamParser.parseAmount(amm["amount"]),
            amount2 = StreamParser.parseAmount(amm["amount2"]),
            lpToken = StreamParser.parseAmount(amm["lp_token"]),
            tradingFee = amm.long("trading_fee")?.toInt() ?: 0,
        )
    }

    /** Raw `tx` lookup, used by the explorer. */
    fun tx(hash: String): JsonObject = rpc.call("tx", buildJsonObject { put("transaction", hash) })

    /** Raw `ledger` header lookup ("validated" or an index), used by the explorer. */
    fun ledger(index: String): JsonObject = rpc.call("ledger", buildJsonObject {
        val n = index.toLongOrNull()
        if (n != null) put("ledger_index", n) else put("ledger_index", index)
    })

    companion object {
        private val pretty = kotlinx.serialization.json.Json { prettyPrint = true }

        fun prettyJson(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)
    }

    private fun sequenceOf(txHash: String): Long? = runCatching {
        val tx = tx(txHash)
        val inner = (tx["tx_json"] as? JsonObject) ?: tx
        if (inner.string("TransactionType") == "EscrowCreate") inner.long("Sequence") else null
    }.getOrNull()

    private fun <T> list(
        method: String,
        key: String,
        address: String,
        extra: JsonObjectBuilder.() -> Unit = {},
        map: (JsonObject) -> T?,
    ): List<T> {
        val result = try {
            rpc.call(method, buildJsonObject {
                put("account", address)
                put("ledger_index", "validated")
                extra()
            })
        } catch (e: RpcException) {
            if (e.error == "actNotFound") return emptyList()
            throw e
        }
        return (result[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(map) }
    }

    private fun JsonObjectBuilder.putAsset(name: String, asset: Asset) {
        putJsonObject(name) {
            when (asset) {
                Asset.Xrp -> put("currency", "XRP")
                is Asset.Token -> {
                    put("currency", Currencies.encode(asset.currency))
                    put("issuer", asset.issuer)
                }
            }
        }
    }

    private fun decodeHex(hex: String): String = runCatching {
        String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(hex)
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
