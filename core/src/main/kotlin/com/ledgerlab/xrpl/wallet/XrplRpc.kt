package com.ledgerlab.xrpl.wallet

import com.ledgerlab.xrpl.StreamParser
import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import com.ledgerlab.xrpl.StreamTx
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RpcException(val error: String, message: String) : IOException(message)

data class AccountState(
    val address: String,
    val balanceDrops: Long,
    val sequence: Long,
    val ownerCount: Int,
)

data class Autofill(val sequence: Long, val feeDrops: Long, val lastLedgerSequence: Long)

data class Reserves(val baseDrops: Long, val incrementDrops: Long)

data class SubmitResult(val engineResult: String, val message: String, val hash: String?) {
    /** `tes` applied, `ter` queued for retry: both may still validate. Everything else failed outright. */
    val accepted: Boolean get() = engineResult.startsWith("tes") || engineResult.startsWith("ter")
}

data class FinalResult(val validated: Boolean, val result: String, val ledgerIndex: Long?)

/**
 * A tiny blocking JSON-RPC client for rippled. Call it from a background dispatcher.
 */
class XrplRpc(
    private val url: String,
    private val http: OkHttpClient = defaultHttp,
) {
    fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("method", method)
            put("params", buildJsonArray { add(params) })
        }.toString().toRequestBody(JSON)
        val response = http.newCall(Request.Builder().url(url).post(body).build()).execute()
        val text = response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} from $url")
            it.body?.string().orEmpty()
        }
        val root = StreamParser.json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Bad response")
        val result = root["result"] as? JsonObject ?: throw IOException("Missing result")
        if (result.string("status") == "error") {
            val error = result.string("error") ?: "error"
            throw RpcException(error, result.string("error_message") ?: error)
        }
        return result
    }

    /** Null when the account doesn't exist yet (it has never received the base reserve). */
    fun accountInfo(address: String, ledger: String = "validated"): AccountState? {
        val result = try {
            call("account_info", buildJsonObject {
                put("account", address)
                put("ledger_index", ledger)
            })
        } catch (e: RpcException) {
            if (e.error == "actNotFound") return null
            throw e
        }
        val data = result["account_data"] as? JsonObject ?: return null
        return AccountState(
            address = address,
            balanceDrops = data.long("Balance") ?: 0L,
            sequence = data.long("Sequence") ?: 0L,
            ownerCount = data.long("OwnerCount")?.toInt() ?: 0,
        )
    }

    fun accountTx(address: String, limit: Int = 20): List<StreamTx> {
        val result = try {
            call("account_tx", buildJsonObject {
                put("account", address)
                put("limit", limit)
                put("ledger_index_min", -1)
                put("ledger_index_max", -1)
            })
        } catch (e: RpcException) {
            if (e.error == "actNotFound") return emptyList()
            throw e
        }
        return (result["transactions"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(StreamParser::parseTx) }
    }

    fun reserves(): Reserves {
        val info = call("server_state")["state"] as? JsonObject
        val ledger = info?.get("validated_ledger") as? JsonObject
        return Reserves(
            baseDrops = ledger?.long("reserve_base") ?: 1_000_000L,
            incrementDrops = ledger?.long("reserve_inc") ?: 200_000L,
        )
    }

    /** Looks up the next Sequence, a sensible fee and an expiry ledger for a new transaction. */
    fun autofill(address: String, ledgerOffset: Int = 20): Autofill {
        val account = accountInfo(address, ledger = "current")
            ?: throw RpcException("actNotFound", "This account isn't on the ledger yet. Fund it first.")
        val fee = call("fee")
        val drops = fee["drops"] as? JsonObject
        val base = drops?.long("base_fee") ?: 10L
        val open = drops?.long("open_ledger_fee") ?: base
        val current = fee.long("ledger_current_index") ?: throw IOException("Missing ledger_current_index")
        return Autofill(
            sequence = account.sequence,
            feeDrops = maxOf(base, open).coerceAtMost(MAX_FEE_DROPS) + FEE_HEADROOM_DROPS,
            lastLedgerSequence = current + ledgerOffset,
        )
    }

    fun submit(blob: String): SubmitResult {
        val result = call("submit", buildJsonObject { put("tx_blob", blob) })
        val tx = (result["tx_json"] as? JsonObject)
        return SubmitResult(
            engineResult = result.string("engine_result") ?: "unknown",
            message = result.string("engine_result_message") ?: "",
            hash = tx?.string("hash") ?: result.string("hash"),
        )
    }

    /** Polls `tx` until the transaction is in a validated ledger or can no longer make it. */
    fun waitForValidation(hash: String, lastLedgerSequence: Long, pollMs: Long = 1_000, maxPolls: Int = 40): FinalResult {
        repeat(maxPolls) {
            val result = try {
                call("tx", buildJsonObject { put("transaction", hash) })
            } catch (e: RpcException) {
                if (e.error != "txnNotFound") throw e
                null
            }
            if (result != null && (result["validated"] as? JsonPrimitive)?.content == "true") {
                val meta = result["meta"] as? JsonObject
                return FinalResult(true, meta?.string("TransactionResult") ?: "unknown", result.long("ledger_index"))
            }
            val validatedIndex = runCatching { call("ledger", ledgerParams()).long("ledger_index") }.getOrNull()
            if (validatedIndex != null && validatedIndex > lastLedgerSequence) {
                return FinalResult(false, "Expired: not included before ledger $lastLedgerSequence", null)
            }
            Thread.sleep(pollMs)
        }
        return FinalResult(false, "Still pending, check the explorer", null)
    }

    private fun ledgerParams(): JsonObject = buildJsonObject { put("ledger_index", "validated") }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_FEE_DROPS = 5_000L
        private const val FEE_HEADROOM_DROPS = 2L

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
