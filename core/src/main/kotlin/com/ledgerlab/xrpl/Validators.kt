package com.ledgerlab.xrpl

import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.xrpl.xrpl4j.codec.addresses.AddressCodec
import org.xrpl.xrpl4j.codec.addresses.UnsignedByteArray
import java.util.Base64

data class ValidatorInfo(
    /** The validator's master public key, base58 `nH…`, or its signing key when no manifest is known. */
    val key: String,
    val domain: String?,
    val onUnl: Boolean,
    /** Share of recent validated ledgers this validator signed the same hash for; null before any are in. */
    val agreement: Double?,
    val validations: Int,
    val lastLedger: Long,
    /** Software version it reported on the last flag ledger, e.g. "2.3.0". */
    val version: String? = null,
)

data class AmendmentVote(
    val id: String,
    val name: String,
    /** Trusted validators voting for it. */
    val votes: Int,
    /** Votes needed for a majority (80% of the UNL). */
    val threshold: Int,
    /** Ripple-epoch seconds when it reached a majority; it activates if it holds for two weeks. */
    val majoritySince: Long?,
)

data class ValidatorSnapshot(
    val validators: List<ValidatorInfo> = emptyList(),
    /** True once the network's published validator list was read, so [ValidatorInfo.onUnl] means something. */
    val unlKnown: Boolean = false,
    val unlSize: Int = 0,
    /** Validators seen that aren't on the list (anyone can run one). */
    val othersSeen: Int = 0,
    val ledgersTracked: Int = 0,
    val amendments: List<AmendmentVote> = emptyList(),
    /** Every amendment the server knows, in [AmendmentStatus.stage] order. */
    val amendmentStatus: List<AmendmentStatus> = emptyList(),
    /** Software versions across the validators shown, most common first. */
    val versions: List<Pair<String, Int>> = emptyList(),
) {
    /** Average agreement across trusted validators that have reported. */
    val networkAgreement: Double?
        get() = validators.filter { it.onUnl || !unlKnown }.mapNotNull { it.agreement }.takeIf { it.isNotEmpty() }?.average()
}

/** What `feature` and the Amendments ledger object say about one amendment. */
data class AmendmentStatus(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val votes: Int?,
    val threshold: Int?,
    val majoritySince: Long? = null,
    /** False when this server's software doesn't know how to apply it. */
    val supported: Boolean = true,
) {
    val stage: AmendmentStage
        get() = when {
            enabled -> AmendmentStage.ENABLED
            majoritySince != null -> AmendmentStage.MAJORITY
            (votes ?: 0) > 0 -> AmendmentStage.VOTING
            else -> AmendmentStage.WAITING
        }
}

enum class AmendmentStage(val label: String) {
    MAJORITY("Majority, activating"),
    VOTING("Being voted on"),
    WAITING("No votes yet"),
    ENABLED("Enabled"),
}

/**
 * Follows the `validations` stream to show which validators agree with the network, and how they vote on
 * amendments. Not thread safe: feed it from one coroutine.
 */
class ValidatorTracker(private val window: Int = 50) {
    private val validated = LinkedHashMap<Long, String>()
    private val states = HashMap<String, State>()
    private val signingToMaster = HashMap<String, String>()
    private val domains = HashMap<String, String>()
    private var unl: Set<String> = emptySet()
    private var amendments: List<AmendmentStatus> = emptyList()

    private class State(val firstLedger: Long) {
        val hashes = HashMap<Long, String>()
        var count = 0
        var lastLedger = 0L
        var votes: List<String>? = null
        var version: String? = null
    }

    fun onValidation(v: Validation) {
        if (v.masterKey != null) signingToMaster[v.signingKey] = v.masterKey
        val state = states.getOrPut(v.signingKey) { State(v.ledgerIndex) }
        state.hashes[v.ledgerIndex] = v.ledgerHash
        state.count++
        state.lastLedger = maxOf(state.lastLedger, v.ledgerIndex)
        if (v.amendments != null) state.votes = v.amendments
        if (v.version != null) state.version = v.version
    }

    fun onValidatedLedger(index: Long, hash: String) {
        if (hash.isEmpty()) return
        validated[index] = hash
        while (validated.size > window + 1) validated.remove(validated.keys.first())
        val oldest = validated.keys.first() - 5
        for (state in states.values) state.hashes.keys.removeAll { it < oldest }
    }

    /** The master keys on the network's published list (UNL). */
    fun setUnl(masterKeys: Collection<String>) {
        unl = masterKeys.toSet()
    }

    fun setManifest(masterKey: String, signingKey: String?, domain: String?) {
        if (signingKey != null) signingToMaster[signingKey] = masterKey
        if (!domain.isNullOrBlank()) domains[masterKey] = domain
    }

    fun setAmendments(list: List<AmendmentStatus>) {
        amendments = list
    }

    /** Master keys of every validator seen so far, for looking up their manifests. */
    fun knownKeys(): Set<String> = states.keys.map { signingToMaster[it] ?: it }.toSet() + unl

    fun snapshot(): ValidatorSnapshot {
        val latest = validated.keys.maxOrNull()
        // The newest ledger is still collecting stragglers, so judge agreement on the ones before it.
        val judged = validated.filterKeys { latest == null || it < latest }
        val grouped = states.entries.groupBy({ signingToMaster[it.key] ?: it.key }, { it.value })
        fun info(key: String, group: List<State>): ValidatorInfo {
            val first = group.minOfOrNull { it.firstLedger } ?: Long.MAX_VALUE
            val considered = judged.filterKeys { it >= first }
            val matched = considered.count { (index, hash) -> group.any { it.hashes[index] == hash } }
            return ValidatorInfo(
                key = key,
                domain = domains[key],
                onUnl = key in unl,
                agreement = if (considered.isEmpty()) null else matched.toDouble() / considered.size,
                validations = group.sumOf { it.count },
                lastLedger = group.maxOfOrNull { it.lastLedger } ?: 0L,
                version = group.firstNotNullOfOrNull { it.version },
            )
        }
        val validators = if (unl.isNotEmpty()) {
            unl.map { info(it, grouped[it].orEmpty()) }
        } else {
            grouped.filter { (_, g) -> g.sumOf { it.count } >= MIN_VALIDATIONS }.map { (k, g) -> info(k, g) }
        }.sortedWith(compareByDescending<ValidatorInfo> { it.agreement ?: -1.0 }.thenBy { it.domain ?: it.key })

        val trusted = grouped.filterKeys { unl.isEmpty() || it in unl }.values
        val tally = HashMap<String, Int>()
        for (group in trusted) {
            group.mapNotNull { it.votes }.firstOrNull()?.forEach { tally.merge(it, 1, Int::plus) }
        }
        val unlSize = if (unl.isNotEmpty()) unl.size else validators.size
        val defaultThreshold = Math.ceil(unlSize * 0.8).toInt()
        val votes = amendments.filter { !it.enabled }.map { a ->
            AmendmentVote(
                id = a.id,
                name = a.name,
                votes = a.votes ?: tally[a.id] ?: 0,
                threshold = a.threshold ?: defaultThreshold,
                majoritySince = a.majoritySince,
            )
        }.filter { it.votes > 0 || it.majoritySince != null }
            .sortedWith(compareByDescending<AmendmentVote> { it.majoritySince != null }.thenByDescending { it.votes })

        val status = amendments.map { a -> if (a.votes == null && tally[a.id] != null) a.copy(votes = tally[a.id]) else a }
            .sortedWith(compareBy<AmendmentStatus> { it.stage.ordinal }.thenByDescending { it.votes ?: 0 }.thenBy { it.name })

        return ValidatorSnapshot(
            validators = validators,
            unlKnown = unl.isNotEmpty(),
            unlSize = unlSize,
            othersSeen = if (unl.isEmpty()) 0 else grouped.keys.count { it !in unl },
            ledgersTracked = judged.size,
            amendments = votes,
            amendmentStatus = status,
            versions = validators.mapNotNull { it.version }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value },
        )
    }

    companion object {
        private const val MIN_VALIDATIONS = 3

        /** The Amendments singleton's ledger object ID; it's the same on every network. */
        const val AMENDMENTS_OBJECT_ID = "7DB0788C020F02780A673DC74757F23823FA3014C1866E72CC4CD8B226CD6EF4"

        /**
         * Reads a published validator list (the JSON at vl.ripple.com and friends), version 1 or 2, into
         * base58 master keys.
         */
        fun parseValidatorList(root: JsonObject): List<String> {
            val blobs = buildList {
                root.string("blob")?.let(::add)
                (root["blobs_v2"] as? JsonArray)?.forEach { (it as? JsonObject)?.string("blob")?.let(::add) }
            }
            return blobs.flatMap { blob ->
                val text = String(Base64.getDecoder().decode(blob))
                val decoded = StreamParser.json.parseToJsonElement(text) as? JsonObject
                (decoded?.get("validators") as? JsonArray).orEmpty().mapNotNull { v ->
                    (v as? JsonObject)?.string("validation_public_key")?.let(::nodeKey)
                }
            }.distinct()
        }

        /** Hex public key from a validator list to the base58 `nH…` form the stream and `manifest` use. */
        fun nodeKey(hex: String): String? = runCatching {
            AddressCodec.getInstance().encodeNodePublicKey(UnsignedByteArray.fromHex(hex))
        }.getOrNull()

        /** The reply to `manifest`: master key, current signing key and domain. */
        fun parseManifest(result: JsonObject): Triple<String, String?, String?>? {
            val details = result["details"] as? JsonObject ?: return null
            val master = details.string("master_key") ?: result.string("requested") ?: return null
            return Triple(master, details.string("ephemeral_key"), details.string("domain"))
        }

        /** The reply to `feature`, plus the Majorities from the Amendments ledger object when available. */
        fun parseAmendments(feature: JsonObject, amendmentsNode: JsonObject?): List<AmendmentStatus> {
            val majorities = (amendmentsNode?.get("Majorities") as? JsonArray).orEmpty().mapNotNull {
                val m = (it as? JsonObject)?.get("Majority") as? JsonObject ?: return@mapNotNull null
                val id = m.string("Amendment") ?: return@mapNotNull null
                id.uppercase() to (m.long("CloseTime") ?: return@mapNotNull null)
            }.toMap()
            val features = feature["features"] as? JsonObject ?: return emptyList()
            return features.mapNotNull { (id, value) ->
                val f = value as? JsonObject ?: return@mapNotNull null
                AmendmentStatus(
                    id = id.uppercase(),
                    name = f.string("name") ?: id.take(8),
                    enabled = (f["enabled"] as? JsonPrimitive)?.content == "true",
                    votes = f.long("count")?.toInt(),
                    threshold = f.long("threshold")?.toInt(),
                    majoritySince = majorities[id.uppercase()],
                    supported = (f["supported"] as? JsonPrimitive)?.content != "false",
                )
            }
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
