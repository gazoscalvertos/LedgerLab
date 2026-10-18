package com.ledgerlab.xrpl

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardStatsTest {
    private fun ledger(index: Long, txns: Int, atMs: Long) = StreamEvent.LedgerClosed(
        LedgerClose(index, closeTime = atMs / 1000, txnCount = txns, feeBaseDrops = 10,
            reserveBaseDrops = 1_000_000, reserveIncDrops = 200_000, receivedAtMs = atMs),
    )

    private fun payment(ledger: Long, drops: Long, result: String = "tesSUCCESS") = StreamEvent.Transaction(
        StreamTx("H$ledger-$drops", "Payment", TxCategory.PAYMENT, "rA", "rB", Amount.Xrp(drops), 12, result, ledger),
    )

    @Test
    fun computesCloseTimeAndTps() {
        val stats = DashboardStats()
        stats.onEvent(ledger(1, 0, 0))
        stats.onEvent(ledger(2, 40, 4_000))
        stats.onEvent(ledger(3, 20, 8_000))
        val snap = stats.snapshot()
        assertEquals(3L, snap.ledgerIndex)
        assertEquals(4.0, snap.avgCloseSeconds, 0.001)
        assertEquals(7.5, snap.tps, 0.001)
        assertEquals(listOf(10.0, 5.0), snap.tpsHistory)
    }

    @Test
    fun tracksWhalesAndVolume() {
        val stats = DashboardStats(whaleCount = 2)
        stats.onEvent(payment(1, 5_000_000))
        stats.onEvent(payment(1, 9_000_000))
        stats.onEvent(payment(1, 1_000_000))
        stats.onEvent(payment(1, 50_000_000, result = "tecUNFUNDED_PAYMENT"))
        val snap = stats.snapshot()
        assertEquals(listOf(9_000_000L, 5_000_000L), snap.whales.map { it.xrpDrops })
        assertEquals(15_000_000L, snap.xrpMovedDrops)
        assertEquals(0.75, snap.successRate, 0.001)
        assertEquals(mapOf(TxCategory.PAYMENT to 4), snap.categoryCounts)
    }

    @Test
    fun ignoresDuplicateLedgers() {
        val stats = DashboardStats()
        stats.onEvent(ledger(5, 10, 0))
        stats.onEvent(ledger(5, 10, 100))
        assertEquals(1L, stats.snapshot().ledgersSeen)
    }

    @Test
    fun resetClearsEverything() {
        val stats = DashboardStats()
        stats.onEvent(ledger(1, 10, 0))
        stats.onEvent(ledger(2, 10, 4_000))
        stats.onEvent(payment(2, 9_000_000))
        stats.reset()
        assertEquals(DashboardSnapshot(), stats.snapshot())
    }

    private fun closed(index: Long, closeSec: Long, atMs: Long, txns: Int = 20) = StreamEvent.LedgerClosed(
        LedgerClose(index, closeTime = closeSec, txnCount = txns, feeBaseDrops = 10,
            reserveBaseDrops = 1_000_000, reserveIncDrops = 200_000, receivedAtMs = atMs),
    )

    @Test
    fun backlogAfterBackgroundDoesNotSkewCloseTime() {
        val stats = DashboardStats()
        (1L..4L).forEach { stats.onEvent(closed(it, it * 4, it * 4_000)) }
        // The app sat in the background for five minutes; Android then delivers the backlog all at once.
        val resumedAt = 316_000L
        (5L..30L).forEach { stats.onEvent(closed(it, it * 4, resumedAt + (it - 5) * 3)) }
        val snap = stats.snapshot()
        assertEquals(4.0, snap.avgCloseSeconds, 0.001)
        assertEquals(5.0, snap.tps, 0.001)
    }

    @Test
    fun missedLedgersRestartTheWindow() {
        val stats = DashboardStats()
        stats.onEvent(closed(1, 4, 4_000))
        stats.onEvent(closed(2, 8, 8_000))
        // Reconnected after missing 50 ledgers.
        stats.onEvent(closed(53, 212, 212_000))
        stats.onEvent(closed(54, 215, 215_500))
        assertEquals(3.5, stats.snapshot().avgCloseSeconds, 0.001)
    }
}
