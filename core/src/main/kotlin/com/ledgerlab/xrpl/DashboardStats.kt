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
    val xrpMovedDrops: Long = 0,
    val successRate: Double = 1.0,
    val txSeen: Long = 0,
    val ledgersSeen: Long = 0,
    /** The biggest XRP payments seen this session, largest first. */
    val whales: List<StreamTx> = emptyList(),
)

/**
 * Rolling aggregates over the live stream. Not thread safe: feed it from a single coroutine.
 */
class DashboardStats(
    private val historySize: Int = 60,
    private val windowLedgers: Int = 20,
    private val whaleCount: Int = 6,
) {
    private val ledgers = ArrayDeque<LedgerClose>()
    private val buckets = LinkedHashMap<Long, Bucket>()
    private val whales = mutableListOf<StreamTx>()
    private var snapshot = DashboardSnapshot()
    private var txSeen = 0L
    private var ledgersSeen = 0L
    private var peakTps = 0.0

    private class Bucket {
        val categories = IntArray(TxCategory.entries.size)
        var xrpDrops = 0L
        var count = 0
        var succeeded = 0
    }

    fun reset() {
        ledgers.clear()
        buckets.clear()
        whales.clear()
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
            StreamEvent.Connected, is StreamEvent.Disconnected -> Unit
        }
    }

    fun snapshot(): DashboardSnapshot {
        val categories = IntArray(TxCategory.entries.size)
        var xrp = 0L
        var count = 0
        var ok = 0
        for (b in buckets.values) {
            for (i in categories.indices) categories[i] += b.categories[i]
            xrp += b.xrpDrops
            count += b.count
            ok += b.succeeded
        }
        return snapshot.copy(
            categoryCounts = TxCategory.entries.associateWith { categories[it.ordinal] }.filterValues { it > 0 },
            xrpMovedDrops = xrp,
            successRate = if (count == 0) 1.0 else ok.toDouble() / count,
            txSeen = txSeen,
            ledgersSeen = ledgersSeen,
            peakTps = peakTps,
            whales = whales.toList(),
        )
    }

    private fun onLedger(ledger: LedgerClose) {
        if (ledgers.lastOrNull()?.index?.let { it >= ledger.index } == true) return
        ledgers.addLast(ledger)
        while (ledgers.size > historySize + 1) ledgers.removeFirst()
        ledgersSeen++

        val history = ledgers.zipWithNext { prev, cur ->
            val seconds = intervalSeconds(prev, cur)
            if (seconds > 0) cur.txnCount / seconds else 0.0
        }
        val recent = ledgers.toList().takeLast(11)
        val span = if (recent.size >= 2) intervalSeconds(recent.first(), recent.last()) else 0.0
        val avgClose = if (recent.size >= 2) span / (recent.size - 1) else snapshot.avgCloseSeconds
        val tps = if (span > 0) recent.drop(1).sumOf { it.txnCount } / span else 0.0
        if (recent.size >= 3) peakTps = maxOf(peakTps, tps)

        snapshot = snapshot.copy(
            ledgerIndex = ledger.index,
            lastCloseAtMs = ledger.receivedAtMs,
            avgCloseSeconds = avgClose,
            tps = tps,
            lastLedgerTxns = ledger.txnCount,
            feeBaseDrops = ledger.feeBaseDrops,
            reserveBaseDrops = ledger.reserveBaseDrops,
            reserveIncDrops = ledger.reserveIncDrops,
            tpsHistory = history,
        )
    }

    private fun onTx(tx: StreamTx) {
        txSeen++
        val bucket = buckets.getOrPut(tx.ledgerIndex) { Bucket() }
        bucket.categories[tx.category.ordinal]++
        bucket.count++
        if (tx.succeeded) bucket.succeeded++
        if (tx.succeeded && tx.category == TxCategory.PAYMENT) bucket.xrpDrops += tx.xrpDrops
        while (buckets.size > windowLedgers) buckets.remove(buckets.keys.first())

        if (tx.succeeded && tx.category == TxCategory.PAYMENT && tx.xrpDrops > 0) {
            if (whales.size < whaleCount || tx.xrpDrops > whales.last().xrpDrops) {
                whales.add(tx)
                whales.sortByDescending { it.xrpDrops }
                while (whales.size > whaleCount) whales.removeAt(whales.lastIndex)
            }
        }
    }

    /**
     * Prefer the gap between arrivals (millisecond precision); fall back to ledger close times,
     * which only have one-second resolution, if arrivals were bunched up (e.g. after a reconnect).
     */
    private fun intervalSeconds(a: LedgerClose, b: LedgerClose): Double {
        val ledgersApart = (b.index - a.index).coerceAtLeast(1)
        val arrival = (b.receivedAtMs - a.receivedAtMs) / 1000.0
        if (arrival >= 1.0 * ledgersApart) return arrival
        return (b.closeTime - a.closeTime).toDouble().coerceAtLeast(0.0)
    }
}
