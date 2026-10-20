package com.ledgerlab.xrpl

import com.ledgerlab.xrpl.StreamParser.long
import com.ledgerlab.xrpl.StreamParser.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/** The time windows the dashboard can show. [LIVE] is the stream; the rest are read back from ledger history. */
enum class StatsPeriod(val label: String, val seconds: Long) {
    LIVE("Live", 0),
    HOUR("1h", 3_600),
    DAY("24h", 86_400),
    WEEK("7d", 604_800),
}

data class LedgerHeader(
    val index: Long,
    /** Seconds since the Ripple epoch. */
    val closeTime: Long,
    /** XRP in existence after this ledger. It only ever falls, as each fee is destroyed. */
    val totalCoinsDrops: Long,
)

data class SampledLedger(val header: LedgerHeader, val txs: List<StreamTx>)

/**
 * Stats for a past window. Close time and burned XRP come straight from the two end ledgers, so they are
 * exact. Counts, mix and volume come from evenly spaced sample ledgers and are scaled up, so they are estimates.
 */
data class PeriodSnapshot(
    val period: StatsPeriod,
    val from: LedgerHeader,
    val to: LedgerHeader,
    val sampled: Int,
    val avgCloseSeconds: Double,
    val estimatedTxs: Long,
    val avgTps: Double,
    val busiestTps: Double,
    val burnedDrops: Long,
    val categoryCounts: Map<TxCategory, Int>,
    val successRate: Double,
    val estimatedXrpMovedDrops: Long,
    /** Transactions per second in each sample ledger, oldest first. */
    val tpsSeries: List<Double>,
    /** The biggest XRP payments in the sample ledgers, largest first. */
    val whales: List<StreamTx>,
    /** True when the node's history didn't reach back over the whole period. */
    val truncated: Boolean,
    /** DEX, AMM, token, NFT and account activity, scaled up from the samples to the whole period. */
    val activity: ActivitySnapshot = ActivitySnapshot(),
) {
    val ledgers: Long get() = to.index - from.index
    val spanSeconds: Long get() = to.closeTime - from.closeTime
}

/**
 * Reads past ledgers from a rippled or Clio node to describe a [StatsPeriod]. Nodes don't keep running totals,
 * so this fetches the ledgers at both ends of the window plus a few in between. Blocking: call it off the main thread.
 */
class LedgerHistory(
    private val call: (String, JsonObject) -> JsonObject,
    private val samples: Int = 24,
    private val parallelism: Int = 4,
) {
    fun load(period: StatsPeriod, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): PeriodSnapshot {
        require(period != StatsPeriod.LIVE) { "Live stats come from the stream" }
        val latest = header(null)
        val earliest = availableFrom(latest.index)
        val (from, truncated) = ledgerAt(latest.closeTime - period.seconds, latest, earliest)
        val indices = sampleIndices(from.index, latest.index, samples)
        val pool = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
        try {
            var done = 0
            onProgress(0, indices.size)
            val futures = indices.map { index -> pool.submit(Callable { sample(index) }) }
            val sampled = futures.map { future ->
                future.get().also { synchronized(this) { onProgress(++done, indices.size) } }
            }
            return summarize(period, from, latest, sampled, truncated)
        } finally {
            pool.shutdownNow()
        }
    }

    /** The validated ledger when [index] is null. */
    fun header(index: Long?): LedgerHeader = parseHeader(ledger(index, transactions = false))

    fun sample(index: Long): SampledLedger {
        val ledger = ledger(index, transactions = true)
        val txs = (ledger["transactions"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(StreamParser::parseTx) }
            .map { it.copy(ledgerIndex = index) }
        return SampledLedger(parseHeader(ledger), txs)
    }

    /** The oldest ledger the node holds without a gap up to [latest]. */
    fun availableFrom(latest: Long): Long {
        val info = runCatching { call("server_info", JsonObject(emptyMap()))["info"] as? JsonObject }.getOrNull()
        return parseCompleteLedgers(info?.string("complete_ledgers"), latest)
    }

    /**
     * Finds the ledger that closed nearest [targetClose]. Starts from a guess at the usual close rate, then
     * corrects using the real rate between the guess and [latest]; two or three hops land within a minute.
     */
    internal fun ledgerAt(targetClose: Long, latest: LedgerHeader, earliest: Long): Pair<LedgerHeader, Boolean> {
        var guess = latest.index - ((latest.closeTime - targetClose) / TYPICAL_CLOSE_SECONDS).toLong()
        val floor = minOf(earliest, latest.index - 1)
        var best: LedgerHeader? = null
        repeat(MAX_HOPS) {
            val clamped = guess.coerceIn(floor, latest.index - 1)
            if (best?.index == clamped) return@repeat
            val h = header(clamped)
            best = h
            val off = h.closeTime - targetClose
            if (abs(off) <= CLOSE_ENOUGH_SECONDS || (clamped == floor && off > 0)) return h to (off > CLOSE_ENOUGH_SECONDS)
            val rate = (latest.closeTime - h.closeTime).toDouble() / (latest.index - h.index)
            guess = h.index - (off / rate.coerceAtLeast(1.0)).toLong()
        }
        val found = best ?: header(floor)
        return found to (found.closeTime - targetClose > CLOSE_ENOUGH_SECONDS)
    }

    private fun ledger(index: Long?, transactions: Boolean): JsonObject {
        val result = call("ledger", buildJsonObject {
            if (index == null) put("ledger_index", "validated") else put("ledger_index", index)
            put("transactions", transactions)
            put("expand", transactions)
        })
        return result["ledger"] as? JsonObject ?: error("No ledger in the reply")
    }

    companion object {
        private const val TYPICAL_CLOSE_SECONDS = 3.9
        private const val CLOSE_ENOUGH_SECONDS = 60L
        private const val MAX_HOPS = 4
        private const val WHALES = 6

        internal fun parseHeader(ledger: JsonObject): LedgerHeader = LedgerHeader(
            index = ledger.long("ledger_index") ?: error("No ledger index"),
            closeTime = ledger.long("close_time") ?: 0L,
            totalCoinsDrops = ledger.long("total_coins") ?: 0L,
        )

        /** `complete_ledgers` looks like "32570-95000000" or "a-b,c-d"; "empty" when the node holds nothing. */
        internal fun parseCompleteLedgers(text: String?, latest: Long): Long {
            val ranges = text.orEmpty().split(',').mapNotNull { part ->
                val bounds = part.trim().split('-')
                val start = bounds.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
                start..(bounds.getOrNull(1)?.toLongOrNull() ?: start)
            }
            return ranges.lastOrNull { latest in it || it.last >= latest - 1 }?.first ?: 1L
        }

        /** [count] ledgers spread evenly through (from, to], always including [to]. */
        internal fun sampleIndices(from: Long, to: Long, count: Int): List<Long> {
            val span = to - from
            if (span <= 0) return listOf(to)
            val n = minOf(count.toLong(), span).toInt()
            return (1..n).map { from + (span * it) / n }.distinct()
        }

        internal fun summarize(
            period: StatsPeriod,
            from: LedgerHeader,
            to: LedgerHeader,
            samples: List<SampledLedger>,
            truncated: Boolean,
        ): PeriodSnapshot {
            val ledgers = (to.index - from.index).coerceAtLeast(1)
            val span = (to.closeTime - from.closeTime).coerceAtLeast(1)
            val avgClose = span.toDouble() / ledgers
            val txs = samples.flatMap { it.txs }
            val perLedger = if (samples.isEmpty()) 0.0 else txs.size.toDouble() / samples.size
            val estimated = (perLedger * ledgers).toLong()
            val payments = txs.filter { it.succeeded && it.category == TxCategory.PAYMENT && it.xrpDrops > 0 }
            val moved = if (samples.isEmpty()) 0L else (payments.sumOf { it.xrpDrops }.toDouble() / samples.size * ledgers).toLong()
            val series = samples.sortedBy { it.header.index }.map { it.txs.size / avgClose }
            val tally = ActivityTally().apply { txs.forEach(::add) }
            val scale = if (samples.isEmpty()) 0.0 else ledgers.toDouble() / samples.size
            return PeriodSnapshot(
                period = period,
                from = from,
                to = to,
                sampled = samples.size,
                avgCloseSeconds = avgClose,
                estimatedTxs = estimated,
                avgTps = estimated.toDouble() / span,
                busiestTps = series.maxOrNull() ?: 0.0,
                burnedDrops = (from.totalCoinsDrops - to.totalCoinsDrops).coerceAtLeast(0),
                categoryCounts = txs.groupingBy { it.category }.eachCount(),
                successRate = if (txs.isEmpty()) 1.0 else txs.count { it.succeeded }.toDouble() / txs.size,
                estimatedXrpMovedDrops = moved,
                tpsSeries = series,
                whales = payments.sortedByDescending { it.xrpDrops }.take(WHALES),
                truncated = truncated,
                activity = tally.snapshot(scale),
            )
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
