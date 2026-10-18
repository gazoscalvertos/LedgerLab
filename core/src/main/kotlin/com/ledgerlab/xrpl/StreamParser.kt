package com.ledgerlab.xrpl

import kotlinx.serialization.json.Json
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
        """{"id":"ledgerlab","command":"subscribe","streams":["ledger","transactions"]}"""

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, receivedAtMs: Long = System.currentTimeMillis()): StreamEvent? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when (root.string("type")) {
            "ledgerClosed" -> parseLedger(root, receivedAtMs)
            "transaction" -> parseTransaction(root)
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
            ),
        )
    }

    private fun parseTransaction(root: JsonObject): StreamEvent? {
        val tx = (root["tx_json"] ?: root["transaction"]) as? JsonObject ?: return null
        val meta = root["meta"] as? JsonObject
        val type = tx.string("TransactionType") ?: return null
        val amount = parseAmount(meta?.get("delivered_amount"))
            ?: parseAmount(tx["Amount"])
            ?: parseAmount(tx["TakerGets"])
        return StreamEvent.Transaction(
            StreamTx(
                hash = root.string("hash") ?: tx.string("hash") ?: "",
                type = type,
                category = TxCategory.of(type),
                account = tx.string("Account") ?: "",
                destination = tx.string("Destination"),
                amount = amount,
                feeDrops = tx.long("Fee") ?: 0L,
                result = meta?.string("TransactionResult") ?: root.string("engine_result") ?: "",
                ledgerIndex = root.long("ledger_index") ?: 0L,
            ),
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

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.content.toLongOrNull()
    }
}
