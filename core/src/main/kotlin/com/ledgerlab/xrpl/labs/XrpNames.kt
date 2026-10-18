package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.StreamParser
import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import com.ledgerlab.xrpl.wallet.RpcException
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A mock name service: `alice.xrp` is an NFT minted by a registry account, and `pay.alice.xrp` is an NFT
 * minted by whoever owns `alice.xrp`. Owning the NFT is owning the name, so names trade like any NFT.
 */
object XrpNames {
    const val TLD = "xrp"

    /** NFTokenTaxon the registry mints top-level names under. */
    const val NAME_TAXON = 5_897_810L

    /** NFTokenTaxon a name's owner mints its subdomains under. */
    const val SUBDOMAIN_TAXON = 5_897_811L

    /** What registering a top-level name costs, paid to the registry. */
    const val REGISTER_FEE_DROPS = 10_000_000L

    /** The registry's royalty on every resale: 5% (units of 1/1000 of a percent). */
    const val REGISTRY_ROYALTY = 5_000

    private const val URI_PREFIX = "xrpns:"
    private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$")

    /** Lower-cases and adds `.xrp` if missing. Null when it isn't a valid name. */
    fun normalize(input: String): String? {
        val n = input.trim().lowercase().removeSuffix(".").let { if (it.endsWith(".$TLD")) it else "$it.$TLD" }
        val labels = n.removeSuffix(".$TLD").split('.')
        if (labels.size !in 1..2 || labels.any { !label.matches(it) }) return null
        return n
    }

    fun isSubdomain(name: String) = name.removeSuffix(".$TLD").contains('.')

    /** `pay.alice.xrp` -> `alice.xrp`; null for a top-level name. */
    fun parentOf(name: String): String? = if (isSubdomain(name)) name.substringAfter('.') else null

    fun uriFor(name: String) = URI_PREFIX + name

    /** The name an NFT URI (decoded text) stands for, if it's one of ours. */
    fun nameFromUri(uri: String?): String? = uri?.takeIf { it.startsWith(URI_PREFIX) }?.removePrefix(URI_PREFIX)?.let(::normalize)

    fun registerMemo(name: String) = "xrpns:register $name"
}

data class NameRecord(
    val name: String,
    val nftId: String,
    /** Null when the server couldn't say (no Clio index available). */
    val owner: String?,
    val issuer: String,
) {
    val isSubdomain: Boolean get() = XrpNames.isSubdomain(name)
}

/**
 * Reads names from the ledger. Uses Clio's `nfts_by_issuer` index (one call lists every name with its
 * current owner) and falls back to the issuer's transaction history when only rippled is reachable.
 */
class NameDirectory(private val rpc: XrplRpc, private val clio: XrplRpc) {
    /** Every live top-level name minted by [registry]. */
    fun names(registry: String): List<NameRecord> = byIssuer(registry, XrpNames.NAME_TAXON)
        .filter { !it.isSubdomain }

    /** Subdomains of [parent] minted by [parentOwner]. Only those minted by the parent's current owner count. */
    fun subdomains(parent: String, parentOwner: String): List<NameRecord> = byIssuer(parentOwner, XrpNames.SUBDOMAIN_TAXON)
        .filter { XrpNames.parentOf(it.name) == parent }

    /** Finds a name (top-level or subdomain) and its owner. */
    fun resolve(name: String, registry: String): NameRecord? {
        val parent = XrpNames.parentOf(name)
        if (parent == null) return names(registry).firstOrNull { it.name == name }
        val top = names(registry).firstOrNull { it.name == parent } ?: return null
        val owner = top.owner ?: return null
        return subdomains(parent, owner).firstOrNull { it.name == name }
    }

    /** The NFT ID an NFTokenMint created, read from its validated metadata. */
    fun mintedNftId(txHash: String): String? = meta(txHash)?.string("nftoken_id")

    /** The offer ID an NFTokenCreateOffer created. */
    fun createdOfferId(txHash: String): String? = meta(txHash)?.string("offer_id")

    private fun meta(txHash: String): JsonObject? {
        val tx = rpc.call("tx", buildJsonObject { put("transaction", txHash) })
        return tx["meta"] as? JsonObject ?: tx["metaData"] as? JsonObject
    }

    private fun byIssuer(issuer: String, taxon: Long): List<NameRecord> = try {
        clioByIssuer(issuer, taxon)
    } catch (e: Exception) {
        if (e is RpcException && e.error == "actNotFound") emptyList() else historyByIssuer(issuer, taxon)
    }

    private fun clioByIssuer(issuer: String, taxon: Long): List<NameRecord> {
        val out = mutableListOf<NameRecord>()
        var marker: kotlinx.serialization.json.JsonElement? = null
        do {
            val result = clio.call("nfts_by_issuer", buildJsonObject {
                put("issuer", issuer)
                put("nft_taxon", taxon)
                put("limit", 100)
                marker?.let { put("marker", it) }
            })
            (result["nfts"] as? JsonArray).orEmpty().forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                if ((o["is_burned"] as? JsonPrimitive)?.content == "true") return@forEach
                val name = XrpNames.nameFromUri(o.string("uri")?.let(::decodeHex)) ?: return@forEach
                out += NameRecord(name, o.string("nft_id") ?: return@forEach, o.string("owner"), issuer)
            }
            marker = result["marker"]
        } while (marker != null && out.size < 1_000)
        return out.distinctBy { it.name }
    }

    /** Without Clio: names minted in the issuer's recent history. Owners are unknown this way. */
    private fun historyByIssuer(issuer: String, taxon: Long): List<NameRecord> {
        val result = rpc.call("account_tx", buildJsonObject {
            put("account", issuer)
            put("limit", 400)
            put("ledger_index_min", -1)
            put("ledger_index_max", -1)
        })
        return (result["transactions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val entry = e as? JsonObject ?: return@mapNotNull null
            val tx = (entry["tx_json"] ?: entry["tx"]) as? JsonObject ?: return@mapNotNull null
            val meta = (entry["meta"] as? JsonObject) ?: return@mapNotNull null
            if (tx.string("TransactionType") != "NFTokenMint" || tx.long("NFTokenTaxon") != taxon) return@mapNotNull null
            if (meta.string("TransactionResult") != "tesSUCCESS") return@mapNotNull null
            val name = XrpNames.nameFromUri(tx.string("URI")?.let(::decodeHex)) ?: return@mapNotNull null
            NameRecord(name, meta.string("nftoken_id") ?: return@mapNotNull null, null, issuer)
        }.distinctBy { it.name }
    }

    private fun decodeHex(hex: String): String = runCatching {
        String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(hex)
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

enum class RecordType(val hint: String) {
    A("IPv4 address, like 203.0.113.7"),
    AAAA("IPv6 address, like 2001:db8::7"),
    CNAME("Another name, like alice.xrp or example.com"),
    TXT("Any text, like a verification code"),
    ;

    /** Null when [value] fits this type, otherwise what's wrong. */
    fun check(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return "Enter a value."
        return when (this) {
            A -> if (ipv4.matches(v) && v.split('.').all { it.toInt() in 0..255 }) null else "That isn't an IPv4 address."
            AAAA -> if (isIpv6(v)) null else "That isn't an IPv6 address."
            CNAME -> if (hostname.matches(v.lowercase().removeSuffix("."))) null else "That isn't a hostname."
            TXT -> if (v.length <= 255) null else "TXT values are at most 255 characters."
        }
    }

    private companion object {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        val hostname = Regex("^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}$")

        fun isIpv6(v: String): Boolean = v.contains(':') && runCatching { java.net.InetAddress.getByName(v) is java.net.Inet6Address }.getOrDefault(false) &&
            v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
    }
}

/** A DNS-style record the owner of a name published on the ledger. */
data class DnsRecord(
    /** The full host, such as `www.alice.xrp` or `alice.xrp`. */
    val name: String,
    val type: RecordType,
    val value: String,
    val ttl: Long,
    /** The account that signed it. Only counts while it owns the name the host sits under. */
    val signer: String,
    val txHash: String,
    val ledgerIndex: Long,
)

/**
 * Name records live in memos: the owner signs a no-op AccountSet whose memo says "set" or "delete" a
 * record. Reading the owner's history in order gives the current zone. Nothing to deploy, and a record
 * stops counting the moment the name changes hands, because the new owner's history is read instead.
 */
object NameRecords {
    const val MEMO_TYPE = "xrpns/record"
    const val DEFAULT_TTL = 3_600L
    private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$")

    /** `www` under `alice.xrp` -> `www.alice.xrp`; blank or `@` is the name itself. Null if invalid. */
    fun hostName(host: String, base: String): String? {
        val h = host.trim().lowercase().removeSuffix(".")
        if (h.isEmpty() || h == "@") return base
        val labels = h.split('.')
        if (labels.size > 3 || labels.any { !label.matches(it) }) return null
        return "$h.$base"
    }

    fun setMemo(name: String, type: RecordType, value: String, ttl: Long = DEFAULT_TTL): String = buildJsonObject {
        put("op", "set")
        put("name", name)
        put("type", type.name)
        put("value", value.trim())
        put("ttl", ttl)
    }.toString()

    fun deleteMemo(name: String, type: RecordType, value: String): String = buildJsonObject {
        put("op", "del")
        put("name", name)
        put("type", type.name)
        put("value", value.trim())
    }.toString()

    /** Several TXT records can share a host; other types hold one value per host. */
    internal fun key(name: String, type: RecordType, value: String) = if (type == RecordType.TXT) "$name|TXT|$value" else "$name|${type.name}"

    /**
     * Replays [ops] (memo JSON with the signer and tx details, oldest first) into the live records.
     */
    internal fun replay(ops: List<Triple<JsonObject, String, Pair<String, Long>>>): List<DnsRecord> {
        val zone = LinkedHashMap<String, DnsRecord>()
        ops.forEach { (o, signer, tx) ->
            val name = o.string("name")?.lowercase() ?: return@forEach
            val type = o.string("type")?.let { t -> RecordType.entries.firstOrNull { it.name == t } } ?: return@forEach
            val value = o.string("value") ?: return@forEach
            when (o.string("op")) {
                "set" -> if (type.check(value) == null) {
                    zone[key(name, type, value)] = DnsRecord(name, type, value.trim(), o.long("ttl") ?: DEFAULT_TTL, signer, tx.first, tx.second)
                }
                "del" -> zone.remove(key(name, type, value.trim()))
            }
        }
        return zone.values.toList()
    }
}

/** The answer to a forward lookup. */
data class Resolution(val query: String, val name: NameRecord?, val records: List<DnsRecord>)

/** Lookups for the public resolver, built on [NameDirectory]. Blocking. */
class NameResolver(private val rpc: XrplRpc, private val directory: NameDirectory) {
    /** Live records [owner] has published, from its recent history. */
    fun recordsBy(owner: String): List<DnsRecord> {
        val result = try {
            rpc.call("account_tx", buildJsonObject {
                put("account", owner)
                put("limit", 400)
                put("ledger_index_min", -1)
                put("ledger_index_max", -1)
                put("forward", true)
            })
        } catch (e: RpcException) {
            if (e.error == "actNotFound") return emptyList()
            throw e
        }
        val memoType = LabTransactions.hex(NameRecords.MEMO_TYPE)
        val ops = (result["transactions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val entry = e as? JsonObject ?: return@mapNotNull null
            val tx = (entry["tx_json"] ?: entry["tx"]) as? JsonObject ?: return@mapNotNull null
            if (tx.string("Account") != owner) return@mapNotNull null
            if ((entry["meta"] as? JsonObject)?.string("TransactionResult") != "tesSUCCESS") return@mapNotNull null
            val memo = (tx["Memos"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("Memo") as? JsonObject }
                .firstOrNull { it.string("MemoType").equals(memoType, ignoreCase = true) } ?: return@mapNotNull null
            val json = memo.string("MemoData")?.let(::decodeHex)?.let { runCatching { StreamParser.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: return@mapNotNull null
            val hash = entry.string("hash") ?: tx.string("hash") ?: ""
            val ledger = entry.long("ledger_index") ?: tx.long("ledger_index") ?: 0L
            Triple(json, owner, hash to ledger)
        }.sortedBy { it.third.second }
        return NameRecords.replay(ops)
    }

    /**
     * `www.pay.alice.xrp`: finds the longest registered name it sits under (pay.alice.xrp, else
     * alice.xrp), then that name's owner's records for exactly this host.
     */
    fun resolve(query: String, registry: String): Resolution {
        val host = query.trim().lowercase().removeSuffix(".").let { if (it.endsWith(".${XrpNames.TLD}")) it else "$it.${XrpNames.TLD}" }
        val labels = host.removeSuffix(".${XrpNames.TLD}").split('.')
        val base = (0 until labels.size).asSequence()
            .map { labels.drop(it).joinToString(".") + ".${XrpNames.TLD}" }
            .filter { it.removeSuffix(".${XrpNames.TLD}").count { c -> c == '.' } <= 1 }
            .mapNotNull { candidate -> XrpNames.normalize(candidate)?.let { directory.resolve(it, registry) } }
            .firstOrNull() ?: return Resolution(host, null, emptyList())
        val owner = base.owner ?: return Resolution(host, base, emptyList())
        return Resolution(host, base, recordsBy(owner).filter { it.name == host })
    }

    /**
     * Reverse lookup. An XRPL address gives the names it owns; an IP gives every host whose A or AAAA
     * record points at it, checked against current ownership.
     */
    fun reverse(query: String, registry: String): List<String> {
        val q = query.trim()
        val all = directory.names(registry).let { names ->
            names + names.filter { it.owner != null }.flatMap { n -> runCatching { directory.subdomains(n.name, n.owner!!) }.getOrDefault(emptyList()) }
        }
        if (q.startsWith("r") && !q.contains('.') && !q.contains(':')) {
            return all.filter { it.owner == q }.map { it.name }.sorted()
        }
        val ip = q.lowercase()
        return all.filter { it.owner != null }.groupBy { it.owner!! }.flatMap { (owner, owned) ->
            val bases = owned.map { it.name }
            runCatching { recordsBy(owner) }.getOrDefault(emptyList())
                .filter { (it.type == RecordType.A || it.type == RecordType.AAAA) && it.value.lowercase() == ip }
                .filter { r -> bases.any { r.name == it || r.name.endsWith(".$it") } }
                .map { it.name }
        }.distinct().sorted()
    }

    private fun decodeHex(hex: String): String = runCatching {
        String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
    }.getOrDefault(hex)
}
