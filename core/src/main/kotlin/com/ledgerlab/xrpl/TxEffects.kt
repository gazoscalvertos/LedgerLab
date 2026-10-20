package com.ledgerlab.xrpl

import com.ledgerlab.xrpl.StreamParser.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a transaction did to the ledger beyond its own fields, read from its metadata: offers it filled,
 * accounts it created, pool liquidity it moved, and so on. Feeds the DEX, token, NFT and account panels.
 */
data class TxEffects(
    /** The trading pair, e.g. "XRP/USD", for offers, AMM transactions and payments that crossed the books. */
    val pair: String? = null,
    /** Offers belonging to someone else that this transaction consumed, fully or in part. */
    val offersFilled: Int = 0,
    val accountsCreated: Int = 0,
    val accountsDeleted: Int = 0,
    /** Issued tokens this transaction touched, as "CUR.rIssuer". */
    val tokens: Set<String> = emptySet(),
    /** Fractional change in an AMM pool's LP token supply: +0.02 means 2% more liquidity. */
    val lpChange: Double? = null,
    /** True when it traded against an AMM pool without being an AMM transaction itself. */
    val ammSwap: Boolean = false,
    /** XRP paid for an NFT, when this transaction accepted a sell or buy offer priced in XRP. */
    val nftSaleDrops: Long? = null,
    /** XRP an EscrowFinish released to its destination. */
    val escrowReleasedDrops: Long? = null,
    /** True when it names a DestinationTag, the way exchanges tell customers' deposits apart. */
    val destinationTag: Boolean = false,
    /** One entry per memo: its decoded MemoType, or "untyped". */
    val memoTypes: List<String> = emptyList(),
) {
    companion object {
        val NONE = TxEffects()

        fun of(tx: JsonObject, meta: JsonObject?): TxEffects {
            val type = tx.string("TransactionType") ?: return NONE
            val nodes = (meta?.get("AffectedNodes") as? JsonArray).orEmpty().mapNotNull { it.node() }
            var filled = 0
            var created = 0
            var deleted = 0
            var lp: Double? = null
            var sale: Long? = null
            var swap = false
            var released: Long? = null
            val account = tx.string("Account")
            for (n in nodes) {
                when (n.entryType) {
                    "Offer" -> {
                        // A consumed offer shows what it gave up in PreviousFields. Cancelled or expired ones don't.
                        val traded = n.kind != Kind.CREATED && n.previous?.containsKey("TakerGets") == true
                        if (traded && n.fields?.string("Account") != account) filled++
                    }
                    "AccountRoot" -> when (n.kind) {
                        // An AMM's own pseudo-account isn't a new user.
                        Kind.CREATED -> if (n.fields?.containsKey("AMMID") != true) created++
                        Kind.DELETED -> deleted++
                        Kind.MODIFIED -> if (n.fields?.containsKey("AMMID") == true && !type.startsWith("AMM")) swap = true
                    }
                    "AMM" -> if (n.kind == Kind.MODIFIED) {
                        val before = n.previous?.lpValue()
                        val after = n.fields?.lpValue()
                        if (before != null && after != null && before > 0) lp = after / before - 1
                    } else if (n.kind == Kind.DELETED) {
                        lp = -1.0
                    }
                    "Escrow" -> if (type == "EscrowFinish" && n.kind == Kind.DELETED) {
                        released = (n.fields?.get("Amount") as? JsonPrimitive)?.content?.toLongOrNull()
                    }
                    "NFTokenOffer" -> if (type == "NFTokenAcceptOffer" && n.kind == Kind.DELETED) {
                        val drops = (n.fields?.get("Amount") as? JsonPrimitive)?.content?.toLongOrNull()
                        if (drops != null && drops > 0) sale = maxOf(sale ?: 0L, drops)
                    }
                }
            }
            val amounts = listOf("Amount", "SendMax", "DeliverMin", "TakerGets", "TakerPays", "LimitAmount", "Amount2")
                .mapNotNull { tx[it] } + listOfNotNull(meta?.get("delivered_amount"))
            val tokens = amounts.mapNotNull { tokenKey(it) }.toSet()
            val pair = when {
                type == "OfferCreate" -> pairOf(tx["TakerGets"], tx["TakerPays"])
                type.startsWith("AMM") -> pairOf(tx["Asset"], tx["Asset2"])
                type == "Payment" && (filled > 0 || swap) -> pairOf(tx["SendMax"] ?: tx["Amount"], tx["Amount"])
                else -> null
            }
            return TxEffects(
                pair = pair,
                offersFilled = filled,
                accountsCreated = created,
                accountsDeleted = deleted,
                tokens = tokens,
                lpChange = lp,
                ammSwap = swap,
                escrowReleasedDrops = released,
                destinationTag = tx.containsKey("DestinationTag"),
                memoTypes = (tx["Memos"] as? JsonArray).orEmpty().map { m ->
                    val memo = (m as? JsonObject)?.get("Memo") as? JsonObject
                    memo?.string("MemoType")?.let(::decodeHexText) ?: "untyped"
                },
                nftSaleDrops = sale,
            )
        }

        /** Memo fields are hex; most types are short ASCII like "text/plain". Anything else shows as hex. */
        internal fun decodeHexText(hex: String): String {
            val bytes = runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
                ?: return hex.take(12)
            val text = String(bytes, Charsets.UTF_8)
            return if (text.isNotEmpty() && text.all { it in ' '..'~' }) text.take(40) else "0x" + hex.take(10).lowercase()
        }

        /** "XRP/USD" with XRP always first, otherwise alphabetical, so both directions count as one pair. */
        internal fun pairOf(a: JsonElement?, b: JsonElement?): String? {
            val x = currencyOf(a) ?: return null
            val y = currencyOf(b) ?: return null
            if (x == y) return null
            val (first, second) = when {
                x == "XRP" -> x to y
                y == "XRP" -> y to x
                x <= y -> x to y
                else -> y to x
            }
            return "$first/$second"
        }

        /** An amount (drops string or IOU object) or an AMM asset spec ({"currency": "XRP"}). */
        private fun currencyOf(element: JsonElement?): String? = when (element) {
            is JsonPrimitive -> if (element.content.toLongOrNull() != null) "XRP" else null
            is JsonObject -> element.string("currency")?.let(StreamParser::decodeCurrency)
            else -> null
        }

        private fun tokenKey(element: JsonElement): String? {
            val obj = element as? JsonObject ?: return null
            val currency = obj.string("currency") ?: return null
            val issuer = obj.string("issuer") ?: return null
            if (currency == "XRP") return null
            return StreamParser.decodeCurrency(currency) + "." + issuer
        }

        private fun JsonObject.lpValue(): Double? =
            ((this["LPTokenBalance"] as? JsonObject)?.string("value"))?.toDoubleOrNull()

        private enum class Kind { CREATED, MODIFIED, DELETED }

        private class Node(val kind: Kind, val entryType: String?, val fields: JsonObject?, val previous: JsonObject?)

        private fun JsonElement.node(): Node? {
            val wrapper = this as? JsonObject ?: return null
            val (kind, body) = when {
                wrapper["CreatedNode"] is JsonObject -> Kind.CREATED to wrapper["CreatedNode"] as JsonObject
                wrapper["ModifiedNode"] is JsonObject -> Kind.MODIFIED to wrapper["ModifiedNode"] as JsonObject
                wrapper["DeletedNode"] is JsonObject -> Kind.DELETED to wrapper["DeletedNode"] as JsonObject
                else -> return null
            }
            val fields = (body["FinalFields"] ?: body["NewFields"]) as? JsonObject
            return Node(kind, body.string("LedgerEntryType"), fields, body["PreviousFields"] as? JsonObject)
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
