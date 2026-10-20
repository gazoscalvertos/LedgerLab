package com.ledgerlab.xrpl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NetworkStatsTest {
    private fun obj(text: String) = StreamParser.json.parseToJsonElement(text) as JsonObject

    private fun tx(text: String) = (StreamParser.parse(text) as StreamEvent.Transaction).tx

    @Test
    fun offerThatCrossesCountsFillsAndPair() {
        val t = tx(
            """{"type":"transaction","ledger_index":9,
               "meta":{"TransactionResult":"tesSUCCESS","AffectedNodes":[
                 {"DeletedNode":{"LedgerEntryType":"Offer","FinalFields":{"Account":"rMaker1","TakerGets":"0"},
                   "PreviousFields":{"TakerGets":"5000000","TakerPays":{"currency":"USD","issuer":"rIss","value":"2"}}}},
                 {"ModifiedNode":{"LedgerEntryType":"Offer","FinalFields":{"Account":"rMaker2"},
                   "PreviousFields":{"TakerGets":"9000000"}}},
                 {"DeletedNode":{"LedgerEntryType":"Offer","FinalFields":{"Account":"rCancel"}}}]},
               "transaction":{"TransactionType":"OfferCreate","Account":"rTaker","Fee":"12",
                 "TakerGets":{"currency":"USD","issuer":"rIss","value":"5"},"TakerPays":"12000000"}}""",
        )
        assertEquals("XRP/USD", t.effects.pair)
        assertEquals(2, t.effects.offersFilled)
        assertEquals(setOf("USD.rIss"), t.effects.tokens)
    }

    @Test
    fun countsNewAccountsButNotAmmAccounts() {
        val t = tx(
            """{"type":"transaction","ledger_index":9,
               "meta":{"TransactionResult":"tesSUCCESS","AffectedNodes":[
                 {"CreatedNode":{"LedgerEntryType":"AccountRoot","NewFields":{"Account":"rNew","Balance":"10000000"}}},
                 {"CreatedNode":{"LedgerEntryType":"AccountRoot","NewFields":{"Account":"rAmm","AMMID":"AB"}}}]},
               "transaction":{"TransactionType":"Payment","Account":"rA","Destination":"rNew","Amount":"10000000","Fee":"12"}}""",
        )
        assertEquals(1, t.effects.accountsCreated)
        assertNull(t.effects.pair)
    }

    @Test
    fun readsAmmLiquidityChangeAndSwaps() {
        val deposit = tx(
            """{"type":"transaction","ledger_index":9,
               "meta":{"TransactionResult":"tesSUCCESS","AffectedNodes":[
                 {"ModifiedNode":{"LedgerEntryType":"AMM",
                   "FinalFields":{"LPTokenBalance":{"currency":"03AB","issuer":"rAmm","value":"110"}},
                   "PreviousFields":{"LPTokenBalance":{"currency":"03AB","issuer":"rAmm","value":"100"}}}}]},
               "transaction":{"TransactionType":"AMMDeposit","Account":"rA","Fee":"12",
                 "Asset":{"currency":"XRP"},"Asset2":{"currency":"USD","issuer":"rIss"}}}""",
        )
        assertEquals("XRP/USD", deposit.effects.pair)
        assertEquals(0.1, deposit.effects.lpChange!!, 1e-9)

        val swap = tx(
            """{"type":"transaction","ledger_index":9,
               "meta":{"TransactionResult":"tesSUCCESS","AffectedNodes":[
                 {"ModifiedNode":{"LedgerEntryType":"AccountRoot","FinalFields":{"Account":"rAmm","AMMID":"AB"},
                   "PreviousFields":{"Balance":"1"}}}]},
               "transaction":{"TransactionType":"Payment","Account":"rA","Destination":"rA","Fee":"12",
                 "Amount":{"currency":"USD","issuer":"rIss","value":"1"},"SendMax":"3000000"}}""",
        )
        assertTrue(swap.effects.ammSwap)

        val tally = ActivityTally().apply { add(deposit); add(swap) }
        val pool = tally.snapshot().pools.single()
        assertEquals("XRP/USD", pool.pair)
        assertEquals(1, pool.deposits)
        assertEquals(1, pool.swaps)
        assertEquals(0.1, pool.liquidityChange, 1e-9)
    }

    @Test
    fun nftSalesAndScaledTallies() {
        val sale = tx(
            """{"type":"transaction","ledger_index":9,
               "meta":{"TransactionResult":"tesSUCCESS","AffectedNodes":[
                 {"DeletedNode":{"LedgerEntryType":"NFTokenOffer","FinalFields":{"Amount":"25000000","Owner":"rS"}}}]},
               "transaction":{"TransactionType":"NFTokenAcceptOffer","Account":"rB","Fee":"12","NFTokenSellOffer":"AA"}}""",
        )
        assertEquals(25_000_000L, sale.effects.nftSaleDrops)
        val snap = ActivityTally().apply { add(sale) }.snapshot(scale = 10.0)
        assertEquals(10L, snap.nftSales)
        assertEquals(250_000_000L, snap.nftSaleVolumeDrops)
        assertEquals(25_000_000L, snap.biggestNftSaleDrops)
    }

    @Test
    fun failedTransactionsDontCount() {
        val failed = tx(
            """{"type":"transaction","ledger_index":9,"meta":{"TransactionResult":"tecKILLED"},
               "transaction":{"TransactionType":"OfferCreate","Account":"rA","Fee":"12",
                 "TakerGets":"1","TakerPays":{"currency":"USD","issuer":"rI","value":"1"}}}""",
        )
        assertEquals(0L, ActivityTally().apply { add(failed) }.snapshot().offersCreated)
    }

    @Test
    fun feePressureLevels() {
        val calm = FeePressure.parse(obj(
            """{"current_ledger_size":"12","current_queue_size":"0","expected_ledger_size":"40","max_queue_size":"800",
               "drops":{"base_fee":"10","median_fee":"5000","open_ledger_fee":"10"},
               "levels":{"open_ledger_level":"256","reference_level":"256"}}""",
        ))
        assertEquals(PressureLevel.CALM, calm.level)
        assertEquals(0.3, calm.fill, 1e-9)
        assertEquals(0.15, calm.gauge, 1e-9)

        val busy = calm.copy(openLedgerLevel = 768, queueSize = 20)
        assertEquals(PressureLevel.BUSY, busy.level)
        assertEquals(3.0, busy.multiplier, 1e-9)
        assertTrue(busy.gauge > 0.5)

        val jammed = calm.copy(openLedgerLevel = 256 * 40, queueSize = 600)
        assertEquals(PressureLevel.CONGESTED, jammed.level)
    }

    @Test
    fun burnedSupply() {
        val s = SupplyState(1, SupplyState.GENESIS_DROPS - 14_000_000_000_000L, SupplyState.GENESIS_DROPS - 13_999_999_000_000L)
        assertEquals(14_000_000_000_000L, s.burnedSinceGenesisDrops)
        assertEquals(1_000_000L, s.burnedThisSessionDrops)
    }

    @Test
    fun parsesValidations() {
        val v = (StreamParser.parse(
            """{"type":"validationReceived","validation_public_key":"n9Sign","master_key":"nHMaster",
               "ledger_index":"88","ledger_hash":"H88","full":true,"amendments":["AA","BB"]}""",
        ) as StreamEvent.ValidationReceived).validation
        assertEquals(88L, v.ledgerIndex)
        assertEquals("nHMaster", v.masterKey)
        assertEquals(listOf("AA", "BB"), v.amendments)
    }

    @Test
    fun trackerScoresAgreement() {
        val tracker = ValidatorTracker()
        tracker.setUnl(listOf("nHGood", "nHLazy", "nHQuiet"))
        tracker.setManifest("nHLazy", "n9Lazy", "lazy.example")
        for (i in 1L..11L) {
            tracker.onValidation(Validation("n9Good", "nHGood", i, "H$i", null))
            if (i % 2 == 0L) tracker.onValidation(Validation("n9Lazy", null, i, "H$i", null))
            else tracker.onValidation(Validation("n9Lazy", null, i, "FORK$i", null))
            tracker.onValidation(Validation("n9Stranger", null, i, "H$i", null))
            tracker.onValidatedLedger(i, "H$i")
        }
        val snap = tracker.snapshot()
        val byKey = snap.validators.associateBy { it.key }
        // Ledger 11 is still collecting validations, so 10 are judged.
        assertEquals(10, snap.ledgersTracked)
        assertEquals(1.0, byKey.getValue("nHGood").agreement!!, 1e-9)
        assertEquals(0.5, byKey.getValue("nHLazy").agreement!!, 1e-9)
        assertEquals("lazy.example", byKey.getValue("nHLazy").domain)
        assertNull(byKey.getValue("nHQuiet").agreement)
        assertEquals(1, snap.othersSeen)
        assertEquals("nHGood", snap.validators.first().key)
    }

    @Test
    fun amendmentVotesFromFeatureAndMajorities() {
        val list = ValidatorTracker.parseAmendments(
            obj("""{"features":{
                "aa11":{"name":"FancyThing","enabled":false,"supported":true,"count":20,"threshold":28},
                "BB22":{"name":"Old","enabled":true,"supported":true},
                "CC33":{"name":"Nobody","enabled":false,"supported":true}}}"""),
            obj("""{"Majorities":[{"Majority":{"Amendment":"AA11","CloseTime":780000000}}]}"""),
        )
        val tracker = ValidatorTracker().apply { setAmendments(list) }
        val votes = tracker.snapshot().amendments
        assertEquals(listOf("FancyThing"), votes.map { it.name })
        assertEquals(20, votes.single().votes)
        assertEquals(780000000L, votes.single().majoritySince)
    }

    @Test
    fun amendmentVotesFallBackToValidations() {
        val tracker = ValidatorTracker()
        tracker.setAmendments(listOf(AmendmentStatus("CC33", "Counted", enabled = false, votes = null, threshold = null)))
        repeat(4) { tracker.onValidation(Validation("n9V$it", null, 256, "H", if (it < 3) listOf("CC33") else emptyList())) }
        val vote = tracker.snapshot().amendments.single()
        assertEquals(3, vote.votes)
    }

    @Test
    fun readsPublishedValidatorList() {
        val hex = "ED" + "11".repeat(32)
        val key = ValidatorTracker.nodeKey(hex)
        assertNotNull(key)
        assertTrue(key!!.startsWith("n"))
        val blob = buildJsonObject {
            put("sequence", 1)
            put("validators", buildJsonArray { add(buildJsonObject { put("validation_public_key", hex) }) })
        }.toString()
        val v1 = buildJsonObject { put("blob", Base64.getEncoder().encodeToString(blob.toByteArray())); put("version", 1) }
        assertEquals(listOf(key), ValidatorTracker.parseValidatorList(v1))
    }

    @Test
    fun dashboardRaisesWhaleAlertsAndHeartbeat() {
        val stats = DashboardStats().apply { whaleThresholdDrops = 1_000_000_000L }
        fun pay(drops: Long) = StreamEvent.Transaction(
            StreamTx("H$drops", "Payment", TxCategory.PAYMENT, "rA", "rB", Amount.Xrp(drops), 12, "tesSUCCESS", 1),
        )
        stats.onEvent(pay(5_000_000L))
        stats.onEvent(pay(2_000_000_000L))
        stats.onEvent(pay(3_000_000_000L))
        assertEquals(listOf(3_000_000_000L, 2_000_000_000L), stats.snapshot().whaleAlerts.map { it.xrpDrops })

        fun ledger(i: Long, ms: Long) = StreamEvent.LedgerClosed(LedgerClose(i, ms / 1000, 0, 10, 1, 1, ms))
        stats.onEvent(ledger(1, 0))
        stats.onEvent(ledger(2, 3_500))
        stats.onEvent(ledger(3, 7_500))
        assertEquals(listOf(3.5, 4.0), stats.snapshot().closeIntervals)
    }
}
