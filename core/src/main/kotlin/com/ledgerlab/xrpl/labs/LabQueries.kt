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

/**
 * One trust line as seen from the queried account. [issuedByMe] is true on the issuer's side, where
 * [issuer] is the queried account itself and [balance] is what holders are owed (shown positive).
 */
data class TrustLine(
    val currency: String,
    val issuer: String,
    val balance: String,
    val limit: String,
    val issuedByMe: Boolean = false,
    val holder: String? = null,
) {
    val token: Asset.Token get() = Asset.Token(currency, issuer)
}

data class AccountOffer(val sequence: Long, val gets: Amount?, val pays: Amount?)

data class BookOffer(val account: String, val gets: Amount?, val pays: Amount?, val quality: Double?)

data class Nft(
    val id: String,
    val uri: String?,
    val taxon: Long,
    val issuer: String,
    val transferable: Boolean,
    /** Royalty in units of 1/1000 of a percent. */
    val transferFee: Int = 0,
    val onlyXrp: Boolean = false,
)

data class NftOffer(
    val id: String,
    val nftId: String,
    val owner: String,
    val amount: Amount?,
    val sell: Boolean,
    val destination: String?,
    /** Seconds since the Ripple epoch. */
    val expiration: Long?,
)

data class PayChannel(
    val id: String,
    val payer: String,
    val payee: String,
    /** Total XRP the payer has put in. */
    val amountDrops: Long,
    /** XRP already paid out to the payee on the ledger. */
    val balanceDrops: Long,
    val publicKey: String,
    val settleDelay: Long,
    /** Set once the payer asks to close: the channel can be removed after this time (Ripple epoch). */
    val expiration: Long?,
    val cancelAfter: Long?,
)

data class EscrowEntry(
    val owner: String,
    val destination: String,
    val amount: Amount?,
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
        val balance = o.string("balance")?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val limit = o.string("limit")?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val peerLimit = o.string("limit_peer")?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val peer = o.string("account") ?: return@list null
        // The issuer's side of a line carries a negative balance, or (before any tokens move) a zero
        // limit facing the holder's positive one.
        val mine = balance.signum() < 0 || (balance.signum() == 0 && limit.signum() == 0 && peerLimit.signum() > 0)
        TrustLine(
            currency = StreamParser.decodeCurrency(o.string("currency") ?: return@list null),
            issuer = if (mine) address else peer,
            balance = balance.abs().stripTrailingZeros().toPlainString(),
            limit = (if (mine) peerLimit else limit).stripTrailingZeros().toPlainString(),
            issuedByMe = mine,
            holder = if (mine) peer else address,
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
            transferFee = o.long("TransferFee")?.toInt() ?: 0,
            onlyXrp = flags and 0x0002L != 0L,
        )
    }

    /** Sell offers (people asking a price) or buy offers (bids) for one NFT. */
    fun nftOffers(nftId: String, sell: Boolean): List<NftOffer> {
        val result = try {
            rpc.call(if (sell) "nft_sell_offers" else "nft_buy_offers", buildJsonObject {
                put("nft_id", nftId)
                put("ledger_index", "validated")
            })
        } catch (e: RpcException) {
            if (e.error == "objectNotFound") return emptyList()
            throw e
        }
        return (result["offers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            NftOffer(
                id = o.string("nft_offer_index") ?: return@mapNotNull null,
                nftId = nftId,
                owner = o.string("owner") ?: "",
                amount = StreamParser.parseAmount(o["amount"]),
                sell = sell,
                destination = o.string("destination"),
                expiration = o.long("expiration"),
            )
        }
    }

    /** NFT offers this account has made, on either side. */
    fun myNftOffers(address: String): List<NftOffer> =
        list("account_objects", "account_objects", address, { put("type", "nft_offer") }) { o ->
            NftOffer(
                id = o.string("index") ?: return@list null,
                nftId = o.string("NFTokenID") ?: return@list null,
                owner = o.string("Owner") ?: address,
                amount = StreamParser.parseAmount(o["Amount"]),
                sell = (o.long("Flags") ?: 0L) and 0x0001L != 0L,
                destination = o.string("Destination"),
                expiration = o.long("Expiration"),
            )
        }

    /** Channels this account pays into or is paid from. */
    fun channels(address: String): List<PayChannel> =
        list("account_objects", "account_objects", address, { put("type", "payment_channel") }) { o ->
            PayChannel(
                id = o.string("index") ?: return@list null,
                payer = o.string("Account") ?: return@list null,
                payee = o.string("Destination") ?: return@list null,
                amountDrops = o.long("Amount") ?: 0L,
                balanceDrops = o.long("Balance") ?: 0L,
                publicKey = o.string("PublicKey") ?: "",
                settleDelay = o.long("SettleDelay") ?: 0L,
                expiration = o.long("Expiration"),
                cancelAfter = o.long("CancelAfter"),
            )
        }

    /** Asks the server to check an off-ledger claim, exactly as a payee would before trusting it. */
    fun verifyClaim(channelId: String, drops: Long, signature: String, publicKey: String): Boolean {
        val result = rpc.call("channel_verify", buildJsonObject {
            put("channel_id", channelId)
            put("amount", drops.toString())
            put("signature", signature)
            put("public_key", publicKey)
        })
        return (result["signature_verified"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    }

    fun escrows(address: String): List<EscrowEntry> {
        val raw = list("account_objects", "account_objects", address, { put("type", "escrow") }) { it }
        return raw.map { o ->
            EscrowEntry(
                owner = o.string("Account") ?: address,
                destination = o.string("Destination") ?: "",
                amount = StreamParser.parseAmount(o["Amount"]),
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
