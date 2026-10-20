package com.ledgerlab.xrpl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Turns raw WebSocket frames from rippled into [StreamEvent]s.
 *
 * Handles both API v1 (`transaction` + nested `hash`) and API v2 (`tx_json` + top-level `hash`).
 */
object StreamParser {
    const val SUBSCRIBE_REQUEST =
        """{"id":"ledgerlab","command":"subscribe","streams":["ledger","transactions","validations"]}"""

    internal val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, receivedAtMs: Long = System.currentTimeMillis()): StreamEvent? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when (root.string("type")) {
            "ledgerClosed" -> parseLedger(root, receivedAtMs)
            "transaction" -> parseTx(root)?.let { StreamEvent.Transaction(it) }
            "validationReceived" -> parseValidation(root)
            "response" -> parseResponse(root)
            else -> null
        }
    }

    private fun parseLedger(root: JsonObject, receivedAtMs: Long): StreamEvent? {
        val index = root.long("ledger_index") ?: return null
        return StreamEvent.LedgerClosed(
            LedgerClose(
                index = index,
                closeTime = root.long("ledger_time") ?: 0L,
                txnCount = root.long("txn_count")?.toInt() ?: 0,
                feeBaseDrops = root.long("fee_base") ?: 0L,
                reserveBaseDrops = root.long("reserve_base") ?: 0L,
                reserveIncDrops = root.long("reserve_inc") ?: 0L,
                receivedAtMs = receivedAtMs,
                hash = root.string("ledger_hash") ?: "",
            ),
        )
    }

    private fun parseValidation(root: JsonObject): StreamEvent? {
        val key = root.string("validation_public_key") ?: return null
        return StreamEvent.ValidationReceived(
            Validation(
                signingKey = key,
                masterKey = root.string("master_key"),
                ledgerIndex = root.long("ledger_index") ?: return null,
                ledgerHash = root.string("ledger_hash") ?: return null,
                amendments = (root["amendments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
                version = (root["server_version"] as? JsonPrimitive)?.content?.let(::decodeServerVersion),
            ),
        )
    }

    /**
     * Parses one transaction envelope: a stream message, an `account_tx` entry (`tx`), a `tx` response or an
     * expanded `ledger` entry.
     * When the envelope has no nested transaction, [root] itself is treated as the transaction.
     */
    fun parseTx(root: JsonObject): StreamTx? {
        val tx = (root["tx_json"] ?: root["transaction"] ?: root["tx"]) as? JsonObject ?: root
        // Ledgers fetched with expand=true carry API v1 metadata as `metaData`.
        val meta = (root["meta"] ?: root["metaData"]) as? JsonObject
        val type = tx.string("TransactionType") ?: return null
        val amount = parseAmount(meta?.get("delivered_amount"))
            ?: parseAmount(tx["Amount"])
            ?: parseAmount(tx["TakerGets"])
        return StreamTx(
            hash = root.string("hash") ?: tx.string("hash") ?: "",
            type = type,
            category = TxCategory.of(type),
            account = tx.string("Account") ?: "",
            destination = tx.string("Destination"),
            amount = amount,
            feeDrops = tx.long("Fee") ?: 0L,
            result = meta?.string("TransactionResult") ?: root.string("engine_result") ?: "",
            ledgerIndex = root.long("ledger_index") ?: tx.long("ledger_index") ?: 0L,
            closeTime = tx.long("date") ?: root.long("date"),
            effects = TxEffects.of(tx, meta),
        )
    }

    private fun parseResponse(root: JsonObject): StreamEvent? {
        if (root.string("status") != "success") return null
        val result = root["result"] as? JsonObject ?: return null
        val index = result.long("ledger_index") ?: return null
        return StreamEvent.Subscribed(
            ledgerIndex = index,
            feeBaseDrops = result.long("fee_base"),
            reserveBaseDrops = result.long("reserve_base"),
            reserveIncDrops = result.long("reserve_inc"),
        )
    }

    /**
     * rippled packs its version into 64 bits: 0x183B (rippled's ID) in the top 16, then major, minor and patch
     * bytes, then a byte whose top two bits say release (11), release candidate (10) or beta (01).
     * Other implementations get a generic label.
     */
    fun decodeServerVersion(raw: String): String? {
        val v = raw.toULongOrNull() ?: return null
        if (v == 0UL) return null
        if ((v shr 48).toInt() != RIPPLED_ID) return "other"
        val major = ((v shr 40) and 0xFFUL).toInt()
        val minor = ((v shr 32) and 0xFFUL).toInt()
        val patch = ((v shr 24) and 0xFFUL).toInt()
        val tag = ((v shr 16) and 0xFFUL).toInt()
        val number = tag and 0x3F
        val suffix = when (tag shr 6) {
            0b10 -> "-rc$number"
            0b01 -> "-b$number"
            else -> ""
        }
        return "$major.$minor.$patch$suffix"
    }

    private const val RIPPLED_ID = 0x183B

    internal fun parseAmount(element: JsonElement?): Amount? = when (element) {
        is JsonPrimitive -> element.content.toLongOrNull()?.let { Amount.Xrp(it) }
        is JsonObject -> {
            val value = element.string("value")
            val currency = element.string("currency")
            if (value != null && currency != null) {
                Amount.Issued(decodeCurrency(currency), value, element.string("issuer"))
            } else {
                null
            }
        }
        else -> null
    }

    /** Non-standard currency codes are 40 hex chars; many of them are ASCII names padded with zeros. */
    internal fun decodeCurrency(code: String): String {
        if (code.length != 40 || !code.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) return code
        val text = code.chunked(2)
            .map { it.toInt(16) }
            .filter { it != 0 }
            .map { it.toChar() }
            .joinToString("")
        return if (text.isNotEmpty() && text.all { it in ' '..'~' }) text.trim() else code.take(8) + "…"
    }

    internal fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.content.toLongOrNull()
    }
}
