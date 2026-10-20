package com.ledgerlab.xrpl

import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The server the dashboard talks to. There's no public count of every node on the network; [peers] is how many
 * other servers this one is linked with.
 */
data class NodeSnapshot(
    val host: String,
    val buildVersion: String? = null,
    /** "full", "proposing", "syncing" and so on. */
    val serverState: String? = null,
    val uptimeSeconds: Long? = null,
    val peers: Int? = null,
    /** Load multiplier: 1.0 is normal, higher means it's charging more because it's busy. */
    val loadFactor: Double? = null,
    val completeLedgers: String? = null,
)

object Nodes {
    /** Reads the `info` object of a `server_info` reply. */
    fun parseServerInfo(host: String, result: JsonObject): NodeSnapshot {
        val info = result["info"] as? JsonObject ?: return NodeSnapshot(host)
        return NodeSnapshot(
            host = host,
            buildVersion = info.string("build_version"),
            serverState = info.string("server_state"),
            uptimeSeconds = info.long("uptime"),
            peers = info.long("peers")?.toInt(),
            loadFactor = (info["load_factor"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
            completeLedgers = info.string("complete_ledgers"),
        )
    }
}
