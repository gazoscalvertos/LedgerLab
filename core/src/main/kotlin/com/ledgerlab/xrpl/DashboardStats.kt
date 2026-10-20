package com.ledgerlab.xrpl

/** A read-only picture of the network the dashboard draws from. */
data class DashboardSnapshot(
    val ledgerIndex: Long = 0,
    val lastCloseAtMs: Long = 0,
    val avgCloseSeconds: Double = 0.0,
    val tps: Double = 0.0,
    val peakTps: Double = 0.0,
    val lastLedgerTxns: Int = 0,
    val feeBaseDrops: Long = 0,
    val reserveBaseDrops: Long = 0,
    val reserveIncDrops: Long = 0,
    /** Transactions per second for each recent ledger, oldest first. */
    val tpsHistory: List<Double> = emptyList(),
    val categoryCounts: Map<TxCategory, Int> = emptyMap(),
    /** The same window by exact transaction type, busiest first. */
    val typeCounts: List<Pair<String, Int>> = emptyList(),
    val xrpMovedDrops: Long = 0,
    val successRate: Double = 1.0,
    val txSeen: Long = 0,
    val ledgersSeen: Long = 0,
    /** The biggest XRP payments seen this session, largest first. */
    val whales: List<StreamTx> = emptyList(),
    /** Payments at or above the whale threshold, newest first. */
    val whaleAlerts: List<StreamTx> = emptyList(),
    /** Seconds between each recent ledger close, oldest first: the network's heartbeat. */
    val closeIntervals: List<Double> = emptyList(),
    /** DEX, AMM, token, NFT and account activity since the dashboard connected. */
    val activity: ActivitySnapshot = ActivitySnapshot(),
)

/**
 * Rolling aggregates over the live stream. Not thread safe: feed it from a single coroutine.
 */
class DashboardStats(
    private val historySize: Int = 60,
    private val windowLedgers: Int = 20,
    private val whaleCount: Int = 6,
    private val alertCount: Int = 8,
) {
    /** XRP payments at or above this many drops raise a whale alert. */
    var whaleThresholdDrops: Long = DEFAULT_WHALE_DROPS

    private val alerts = ArrayDeque<StreamTx>()
    private val closeIntervals = ArrayDeque<Double>()
    private val activity = ActivityTally()
    private val ledgers = ArrayDeque<LedgerClose>()
    private val buckets = LinkedHashMap<Long, Bucket>()
    private val whales = mutableListOf<StreamTx>()
    private val tpsHistory = ArrayDeque<Double>()
    private var snapshot = DashboardSnapshot()
    private var txSeen = 0L
    private var ledgersSeen = 0L
    private var peakTps = 0.0

    private class Bucket {
        val categories = IntArray(TxCategory.entries.size)
        val types = HashMap<String, Int>()
        var xrpDrops = 0L
        var count = 0
        var succeeded = 0
    }

    fun reset() {
        ledgers.clear()
        tpsHistory.clear()
        buckets.clear()
        whales.clear()
        alerts.clear()
        closeIntervals.clear()
        activity.reset()
        txSeen = 0
        ledgersSeen = 0
        peakTps = 0.0
        snapshot = DashboardSnapshot()
    }

    fun onEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.Subscribed -> snapshot = snapshot.copy(
                ledgerIndex = event.ledgerIndex,
                feeBaseDrops = event.feeBaseDrops ?: snapshot.feeBaseDrops,
                reserveBaseDrops = event.reserveBaseDrops ?: snapshot.reserveBaseDrops,
                reserveIncDrops = event.reserveIncDrops ?: snapshot.reserveIncDrops,
            )
            is StreamEvent.LedgerClosed -> onLedger(event.ledger)
            is StreamEvent.Transaction -> onTx(event.tx)
            StreamEvent.Connected, is StreamEvent.Disconnected, is StreamEvent.ValidationReceived -> Unit
        }
    }

    fun snapshot(): DashboardSnapshot {
        val categories = IntArray(TxCategory.entries.size)
        var xrp = 0L
        var count = 0
        var ok = 0
        val types = HashMap<String, Int>()
        for (b in buckets.values) {
            for (i in categories.indices) categories[i] += b.categories[i]
            for ((type, n) in b.types) types.merge(type, n, Int::plus)
            xrp += b.xrpDrops
            count += b.count
            ok += b.succeeded
        }
        return snapshot.copy(
            categoryCounts = TxCategory.entries.associateWith { categories[it.ordinal] }.filterValues { it > 0 },
            typeCounts = types.entries.sortedByDescending { it.value }.map { it.key to it.value },
            xrpMovedDrops = xrp,
            successRate = if (count == 0) 1.0 else ok.toDouble() / count,
            txSeen = txSeen,
            ledgersSeen = ledgersSeen,
            peakTps = peakTps,
            whales = whales.toList(),
            whaleAlerts = alerts.toList(),
            closeIntervals = closeIntervals.toList(),
            activity = activity.snapshot(),
        )
    }

    private fun onLedger(ledger: LedgerClose) {
        val last = ledgers.lastOrNull()
        if (last != null && last.index >= ledger.index) return
        // A gap means timing across it is meaningless: ledgers were missed (a reconnect), or this one
        // arrived long after it closed (the app was in the background and Android froze the socket).
        // Start the timing window again from here rather than averaging the time away into the stats.
        if (last != null && (ledger.index != last.index + 1 || arrivedLate(last, ledger))) ledgers.clear()
        val prev = ledgers.lastOrNull()
        ledgers.addLast(ledger)
        while (ledgers.size > WINDOW + 1) ledgers.removeFirst()
        ledgersSeen++

        if (prev != null) {
            val seconds = intervalSeconds(prev, ledger)
            if (seconds > 0) {
                tpsHistory.addLast(ledger.txnCount / seconds)
                while (tpsHistory.size > historySize) tpsHistory.removeFirst()
                closeIntervals.addLast(seconds)
                while (closeIntervals.size > historySize) closeIntervals.removeFirst()
            }
        }
        val recent = ledgers.toList()
        val span = recent.zipWithNext(::intervalSeconds).sum()
        val warm = recent.size >= 2 && span > 0
        val avgClose = if (warm) span / (recent.size - 1) else snapshot.avgCloseSeconds
        val tps = if (warm) recent.drop(1).sumOf { it.txnCount } / span else snapshot.tps
        if (recent.size >= 3 && warm) peakTps = maxOf(peakTps, tps)

        snapshot = snapshot.copy(
            ledgerIndex = ledger.index,
            lastCloseAtMs = ledger.receivedAtMs,
            avgCloseSeconds = avgClose,
            tps = tps,
            lastLedgerTxns = ledger.txnCount,
            feeBaseDrops = ledger.feeBaseDrops,
            reserveBaseDrops = ledger.reserveBaseDrops,
            reserveIncDrops = ledger.reserveIncDrops,
            tpsHistory = tpsHistory.toList(),
        )
    }

    private fun onTx(tx: StreamTx) {
        txSeen++
        val bucket = buckets.getOrPut(tx.ledgerIndex) { Bucket() }
        bucket.categories[tx.category.ordinal]++
        bucket.types.merge(tx.type, 1, Int::plus)
        bucket.count++
        if (tx.succeeded) bucket.succeeded++
        if (tx.succeeded && tx.category == TxCategory.PAYMENT) bucket.xrpDrops += tx.xrpDrops
        while (buckets.size > windowLedgers) buckets.remove(buckets.keys.first())

        activity.add(tx)
        if (tx.succeeded && tx.category == TxCategory.PAYMENT && tx.xrpDrops >= whaleThresholdDrops) {
            alerts.addFirst(tx)
            while (alerts.size > alertCount) alerts.removeLast()
        }
        if (tx.succeeded && tx.category == TxCategory.PAYMENT && tx.xrpDrops > 0) {
            if (whales.size < whaleCount || tx.xrpDrops > whales.last().xrpDrops) {
                whales.add(tx)
                whales.sortByDescending { it.xrpDrops }
                while (whales.size > whaleCount) whales.removeAt(whales.lastIndex)
            }
        }
    }

    /**
     * Prefer the gap between arrivals (millisecond precision). Fall back to the ledger close times when
     * arrivals were bunched up, e.g. a backlog delivered at once after the app comes back to the front.
     * Close times are rounded by the network (up to 10 s), so they only serve as the fallback.
     */
    private fun intervalSeconds(a: LedgerClose, b: LedgerClose): Double {
        val arrival = (b.receivedAtMs - a.receivedAtMs) / 1000.0
        if (arrival >= 1.0) return arrival
        return (b.closeTime - a.closeTime).toDouble().coerceAtLeast(0.0)
    }

    /** True when [b] reached us far later than the network closed it, relative to [a]. */
    private fun arrivedLate(a: LedgerClose, b: LedgerClose): Boolean {
        val arrival = (b.receivedAtMs - a.receivedAtMs) / 1000.0
        val closed = (b.closeTime - a.closeTime).toDouble()
        return arrival > closed + LATE_TOLERANCE_SECONDS
    }

    companion object {
        /** The whale threshold until the user picks another: 50,000 XRP. */
        const val DEFAULT_WHALE_DROPS = 50_000L * 1_000_000L

        /** Ledgers in the rolling average for close time and throughput. */
        private const val WINDOW = 10

        /** Close times are rounded to as much as 10 s, plus network jitter. */
        private const val LATE_TOLERANCE_SECONDS = 15.0
    }
}
