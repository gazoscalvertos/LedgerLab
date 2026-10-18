package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import com.ledgerlab.xrpl.wallet.RpcException
import com.ledgerlab.xrpl.wallet.XrplRpc
import com.ripple.cryptoconditions.CryptoConditionWriter
import com.ripple.cryptoconditions.PreimageSha256Fulfillment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

/** Proof of existence: a file's SHA-256 written into a memo, timestamped by the ledger it lands in. */
object Notary {
    const val MEMO_TYPE = "ledgerlab/notary-sha256"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
}

data class NotaryRecord(
    val sha256: String,
    val label: String,
    val txHash: String,
    val ledgerIndex: Long,
    /** Seconds since the Ripple epoch. */
    val closeTime: Long?,
)

/** A secret-locked escrow: the condition goes on the ledger, the fulfillment stays with whoever may release it. */
data class EscrowLock(val conditionHex: String, val fulfillmentHex: String)

object EscrowLocks {
    private val random = SecureRandom()

    /** A fresh random 32-byte preimage, as a PREIMAGE-SHA-256 condition and its fulfillment. */
    fun newLock(): EscrowLock {
        val preimage = ByteArray(32).also(random::nextBytes)
        val fulfillment = PreimageSha256Fulfillment.from(preimage)
        return EscrowLock(
            conditionHex = toHex(CryptoConditionWriter.writeCondition(fulfillment.derivedCondition)),
            fulfillmentHex = toHex(CryptoConditionWriter.writeFulfillment(fulfillment)),
        )
    }

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
}

/** An event's tickets are NFTs from the organiser, grouped under a taxon derived from the event name. */
object Tickets {
    private const val URI_PREFIX = "xrptix:"

    fun taxonFor(event: String): Long = CRC32().apply { update(event.trim().lowercase().toByteArray()) }.value and 0x7FFFFFFFL

    fun uriFor(event: String, number: Int) = "$URI_PREFIX${event.trim()}#$number"

    /** (event, ticket number) from a ticket NFT's URI. */
    fun parse(uri: String?): Pair<String, Int>? {
        if (uri == null || !uri.startsWith(URI_PREFIX)) return null
        val body = uri.removePrefix(URI_PREFIX)
        val number = body.substringAfterLast('#', "").toIntOrNull() ?: return null
        return body.substringBeforeLast('#') to number
    }
}

/** An NFT from Clio's issuer index, with its current owner. */
data class IssuedNft(val id: String, val owner: String?, val uri: String?, val transferable: Boolean, val burned: Boolean = false)

/** Shared reads for the mockup apps. Blocking. */
class AppQueries(private val rpc: XrplRpc, private val clio: XrplRpc) {
    /** NFTs an account issued under [taxon], burned ones included, via Clio's nfts_by_issuer. */
    fun nftsByIssuer(issuer: String, taxon: Long): List<IssuedNft> {
        val out = mutableListOf<IssuedNft>()
        var marker: JsonElement? = null
        do {
            val result = try {
                clio.call("nfts_by_issuer", buildJsonObject {
                    put("issuer", issuer)
                    put("nft_taxon", taxon)
                    put("limit", 100)
                    marker?.let { put("marker", it) }
                })
            } catch (e: RpcException) {
                if (e.error == "actNotFound" || e.error == "objectNotFound") return emptyList()
                throw e
            }
            (result["nfts"] as? JsonArray).orEmpty().forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                out += IssuedNft(
                    id = o.string("nft_id") ?: return@forEach,
                    owner = o.string("owner"),
                    uri = o.string("uri")?.let(::decodeHex),
                    transferable = (o.long("flags") ?: 0L) and 0x0008L != 0L,
                    burned = (o["is_burned"] as? JsonPrimitive)?.content == "true",
                )
            }
            marker = result["marker"]
        } while (marker != null && out.size < 1_000)
        return out
    }

    /** Notarised hashes in [address]'s recent history, newest first. */
    fun notarizations(address: String, limit: Int = 200): List<NotaryRecord> {
        val result = try {
            rpc.call("account_tx", buildJsonObject {
                put("account", address)
                put("limit", limit)
                put("ledger_index_min", -1)
                put("ledger_index_max", -1)
            })
        } catch (e: RpcException) {
            if (e.error == "actNotFound") return emptyList()
            throw e
        }
        val memoType = LabTransactions.hex(Notary.MEMO_TYPE)
        return (result["transactions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val entry = e as? JsonObject ?: return@mapNotNull null
            val tx = (entry["tx_json"] ?: entry["tx"]) as? JsonObject ?: return@mapNotNull null
            val meta = entry["meta"] as? JsonObject
            if (meta?.string("TransactionResult") != "tesSUCCESS") return@mapNotNull null
            val memo = (tx["Memos"] as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("Memo") as? JsonObject) }
                .firstOrNull { it.string("MemoType").equals(memoType, ignoreCase = true) } ?: return@mapNotNull null
            NotaryRecord(
                sha256 = memo.string("MemoData")?.uppercase() ?: return@mapNotNull null,
                label = memo.string("MemoFormat")?.let(::decodeHex).orEmpty(),
                txHash = entry.string("hash") ?: tx.string("hash") ?: "",
                ledgerIndex = entry.long("ledger_index") ?: tx.long("ledger_index") ?: 0L,
                closeTime = tx.long("date") ?: entry.long("date") ?: entry.string("close_time_iso")?.let(::isoToRipple),
            )
        }
    }

    /** The validated metadata's NFT ID or offer ID for a transaction that created one. */
    fun createdId(txHash: String, field: String): String? {
        val tx = rpc.call("tx", buildJsonObject { put("transaction", txHash) })
        return ((tx["meta"] ?: tx["metaData"]) as? JsonObject)?.string(field)
    }

    /** The Sequence an EscrowCreate used, which EscrowFinish and EscrowCancel refer to. */
    fun sequenceOf(txHash: String): Long? {
        val tx = rpc.call("tx", buildJsonObject { put("transaction", txHash) })
        val inner = (tx["tx_json"] as? JsonObject) ?: tx
        return inner.long("Sequence")
    }

    private fun isoToRipple(iso: String): Long? = runCatching { java.time.Instant.parse(iso).epochSecond - 946_684_800L }.getOrNull()

    private fun decodeHex(hex: String): String = runCatching {
        String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(hex)
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
