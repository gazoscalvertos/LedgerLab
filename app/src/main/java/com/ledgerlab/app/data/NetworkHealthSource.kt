package com.ledgerlab.app.data

import com.ledgerlab.xrpl.AmendmentStatus
import com.ledgerlab.xrpl.FeePressure
import com.ledgerlab.xrpl.LedgerHeader
import com.ledgerlab.xrpl.LedgerHistory
import com.ledgerlab.xrpl.NodeSnapshot
import com.ledgerlab.xrpl.Nodes
import com.ledgerlab.xrpl.ValidatorTracker
import com.ledgerlab.xrpl.XrplNetwork
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.io.IOException
import java.net.URI

/**
 * The polled half of the dashboard: things the stream doesn't push, like fee escalation, the XRP supply and
 * the trusted validator list. Every call blocks, so run them on [kotlinx.coroutines.Dispatchers.IO].
 */
class NetworkHealthSource(private val network: XrplNetwork) {
    private val rpc = XrplRpc(network.rpcUrl)

    fun fee(): FeePressure = FeePressure.parse(rpc.call("fee"))

    /** The validated ledger's header, which carries `total_coins`. */
    fun supply(): LedgerHeader = LedgerHistory(rpc::call).header(null)

    /** Master keys on the network's published validator list. */
    fun validatorList(): List<String> {
        val request = Request.Builder().url(network.validatorListUrl).build()
        val text = XrplRpc.defaultHttp.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} from ${network.validatorListUrl}")
            it.body?.string().orEmpty()
        }
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Bad validator list")
        return ValidatorTracker.parseValidatorList(root)
    }

    /** Master key, signing key and domain for one validator. */
    fun manifest(key: String): Triple<String, String?, String?>? =
        ValidatorTracker.parseManifest(rpc.call("manifest", buildJsonObject { put("public_key", key) }))

    /** The status of the server the dashboard talks to: its version, state, load and how many peers it has. */
    fun nodes(): NodeSnapshot = Nodes.parseServerInfo(URI(network.wsUrl).host, rpc.call("server_info"))

    fun amendments(): List<AmendmentStatus> {
        val feature = rpc.call("feature")
        val node = runCatching {
            rpc.call("ledger_entry", buildJsonObject {
                put("index", ValidatorTracker.AMENDMENTS_OBJECT_ID)
                put("ledger_index", "validated")
            })["node"] as? JsonObject
        }.getOrNull()
        return ValidatorTracker.parseAmendments(feature, node)
    }
}
