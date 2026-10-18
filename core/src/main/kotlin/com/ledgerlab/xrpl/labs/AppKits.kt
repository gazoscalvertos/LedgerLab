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

/** A cheque waiting to be cashed. [sendMax] is the most it can pay. */
data class LedgerCheck(
    val id: String,
    val from: String,
    val to: String,
    val sendMax: com.ledgerlab.xrpl.Amount?,
    /** Ripple epoch; null if it never expires. */
    val expiration: Long?,
    val invoiceId: String?,
)

/** An on-ledger credential: [issuer] vouches that [subject] is [type]. */
data class LedgerCredential(
    val issuer: String,
    val subject: String,
    val type: String,
    val accepted: Boolean,
    val expiration: Long?,
    val uri: String?,
)

data class SignerList(val quorum: Int, val signers: List<String>)

/** Account flags the mockup apps care about, from account_info. */
data class AccountFlags(val raw: Long) {
    val defaultRipple: Boolean get() = raw and 0x0080_0000L != 0L
    val requireAuth: Boolean get() = raw and 0x0004_0000L != 0L
    val allowClawback: Boolean get() = raw and 0x8000_0000L != 0L
    val depositAuth: Boolean get() = raw and 0x0100_0000L != 0L
    val masterDisabled: Boolean get() = raw and 0x0010_0000L != 0L
}

/** Plain text <-> the hex the ledger stores for memos, credential types and URIs. */
object LedgerText {
    fun encode(text: String) = LabTransactions.hex(text)

    fun decode(hex: String): String = runCatching {
        String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(hex)

    /** A 256-bit ID derived from text, for fields such as a Check's InvoiceID. */
    fun idFor(text: String): String = Notary.sha256Hex(text.toByteArray())
}

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

    fun checks(address: String): List<LedgerCheck> = objects(address, "check").mapNotNull { o ->
        LedgerCheck(
            id = o.string("index") ?: return@mapNotNull null,
            from = o.string("Account") ?: return@mapNotNull null,
            to = o.string("Destination") ?: return@mapNotNull null,
            sendMax = com.ledgerlab.xrpl.StreamParser.parseAmount(o["SendMax"]),
            expiration = o.long("Expiration"),
            invoiceId = o.string("InvoiceID"),
        )
    }

    fun credentials(address: String): List<LedgerCredential> = objects(address, "credential").mapNotNull { o ->
        LedgerCredential(
            issuer = o.string("Issuer") ?: return@mapNotNull null,
            subject = o.string("Subject") ?: return@mapNotNull null,
            type = o.string("CredentialType")?.let(::decodeHex) ?: return@mapNotNull null,
            accepted = (o.long("Flags") ?: 0L) and 0x0001_0000L != 0L,
            expiration = o.long("Expiration"),
            uri = o.string("URI")?.let(::decodeHex),
        )
    }

    fun signerList(address: String): SignerList? {
        val o = objects(address, "signer_list").firstOrNull() ?: return null
        val entries = (o["SignerEntries"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("SignerEntry") as? JsonObject)?.string("Account")
        }
        return SignerList((o.long("SignerQuorum") ?: 0L).toInt(), entries)
    }

    fun flags(address: String): AccountFlags? = try {
        val result = rpc.call("account_info", buildJsonObject {
            put("account", address)
            put("ledger_index", "validated")
        })
        (result["account_data"] as? JsonObject)?.long("Flags")?.let(::AccountFlags)
    } catch (e: RpcException) {
        if (e.error == "actNotFound") null else throw e
    }

    /** The open ledger's base fee and index, for transactions that must be built ahead of signing. */
    fun feeAndLedger(): Pair<Long, Long> {
        val fee = rpc.call("fee")
        val base = ((fee["drops"] as? JsonObject)?.long("base_fee")) ?: 10L
        return base to (fee.long("ledger_current_index") ?: 0L)
    }

    fun nextSequence(address: String): Long? = rpc.accountInfo(address, ledger = "current")?.sequence

    private fun objects(address: String, type: String): List<JsonObject> {
        val result = try {
            rpc.call("account_objects", buildJsonObject {
                put("account", address)
                put("ledger_index", "validated")
                put("type", type)
            })
        } catch (e: RpcException) {
            if (e.error == "actNotFound") return emptyList()
            throw e
        }
        return (result["account_objects"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
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
