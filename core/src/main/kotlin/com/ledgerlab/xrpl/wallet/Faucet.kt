package com.ledgerlab.xrpl.wallet

import com.ledgerlab.xrpl.StreamParser
import com.ledgerlab.xrpl.XrplNetwork
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The public Testnet/Devnet faucets hand out free test XRP to any address. */
class Faucet(private val http: OkHttpClient = XrplRpc.defaultHttp) {
    /** Funds [address] and returns the XRP amount granted, if the faucet said. Blocking. */
    fun fund(network: XrplNetwork, address: String): Double? {
        val url = network.faucetUrl ?: throw IOException("${network.label} has no faucet: it uses real XRP.")
        val body = buildJsonObject {
            put("destination", address)
            put("userAgent", "LedgerLab")
        }.toString().toRequestBody("application/json".toMediaType())
        val text = http.newCall(Request.Builder().url(url).post(body).build()).execute().use {
            if (!it.isSuccessful) throw IOException("The faucet answered HTTP ${it.code}. It may be busy; try again shortly.")
            it.body?.string().orEmpty()
        }
        val root = runCatching { StreamParser.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        return (root?.get("amount") as? JsonPrimitive)?.doubleOrNull
    }
}
