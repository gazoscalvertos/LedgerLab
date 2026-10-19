package com.ledgerlab.xrpl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerHistoryTest {
    /** A fake node: ledgers close every 4 s, each holds two payments (one failed), and burns 24 drops. */
    private class FakeNode(val latest: Long, val earliest: Long = 1) {
        var calls = 0

        fun closeOf(index: Long) = 1_000_000L + index * 4
        fun coinsOf(index: Long) = 100_000_000_000L - index * 24

        fun call(method: String, params: JsonObject): JsonObject {
            calls++
            if (method == "server_info") {
                return buildJsonObject { put("info", buildJsonObject { put("complete_ledgers", "$earliest-$latest") }) }
            }
            val raw = params["ledger_index"] as JsonPrimitive
            val index = if (raw.isString) latest else raw.long
            require(index in earliest..latest) { "lgrNotFound $index" }
            val expand = (params["transactions"] as JsonPrimitive).content == "true"
            return buildJsonObject {
                put("ledger", buildJsonObject {
                    put("ledger_index", index.toString())
                    put("close_time", closeOf(index))
                    put("total_coins", coinsOf(index).toString())
                    if (expand) put("transactions", buildJsonArray {
                        add(tx(index, 1_000_000 * index, "tesSUCCESS"))
                        add(tx(index, 5, "tecUNFUNDED_PAYMENT"))
                    })
                })
            }
        }

        private fun tx(index: Long, drops: Long, result: String) = buildJsonObject {
            put("TransactionType", "Payment")
            put("Account", "rA")
            put("Destination", "rB")
            put("Amount", drops.toString())
            put("Fee", "12")
            put("hash", "H$index-$drops")
            put("metaData", buildJsonObject { put("TransactionResult", result) })
        }
    }

    @Test
    fun findsTheWindowAndSummarizesIt() {
        val node = FakeNode(latest = 100_000)
        val snap = LedgerHistory(node::call, samples = 10).load(StatsPeriod.HOUR)
        // 3600 s at 4 s a ledger is 900 ledgers back.
        assertTrue(snap.from.index in 99_085L..99_115L)
        assertEquals(100_000L, snap.to.index)
        assertFalse(snap.truncated)
        assertEquals(4.0, snap.avgCloseSeconds, 0.001)
        assertEquals(10, snap.sampled)
        assertEquals(snap.ledgers * 2, snap.estimatedTxs)
        assertEquals(0.5, snap.avgTps, 0.001)
        assertEquals(0.5, snap.successRate, 0.001)
        assertEquals(snap.ledgers * 24, snap.burnedDrops)
        assertEquals(mapOf(TxCategory.PAYMENT to 20), snap.categoryCounts)
        assertEquals(100_000_000_000L, snap.whales.first().xrpDrops)
        assertEquals(10, snap.tpsSeries.size)
    }

    @Test
    fun flagsShortHistory() {
        val node = FakeNode(latest = 100_000, earliest = 99_000)
        val snap = LedgerHistory(node::call, samples = 5).load(StatsPeriod.DAY)
        assertEquals(99_000L, snap.from.index)
        assertTrue(snap.truncated)
        assertEquals(1_000L, snap.ledgers)
    }

    @Test
    fun parsesCompleteLedgers() {
        assertEquals(32_570L, LedgerHistory.parseCompleteLedgers("32570-95000000", 95_000_000))
        assertEquals(900L, LedgerHistory.parseCompleteLedgers("1-500,900-1000", 1_000))
        assertEquals(1L, LedgerHistory.parseCompleteLedgers("empty", 1_000))
    }

    @Test
    fun spreadsSamplesEvenly() {
        assertEquals(listOf(25L, 50L, 75L, 100L), LedgerHistory.sampleIndices(0, 100, 4))
        assertEquals(listOf(11L, 12L), LedgerHistory.sampleIndices(10, 12, 24))
    }
}
