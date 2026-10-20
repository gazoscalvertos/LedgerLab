package com.ledgerlab.xrpl

import org.junit.Assert.assertEquals
import org.junit.Test

class BreakdownTest {
    private fun tx(text: String) = (StreamParser.parse(text) as StreamEvent.Transaction).tx

    @Test
    fun modernTypesHaveRealCategories() {
        mapOf(
            "OracleSet" to TxCategory.ORACLE,
            "MPTokenIssuanceCreate" to TxCategory.MPT,
            "MPTokenAuthorize" to TxCategory.MPT,
            "CredentialCreate" to TxCategory.IDENTITY,
            "DIDSet" to TxCategory.IDENTITY,
            "PermissionedDomainSet" to TxCategory.IDENTITY,
            "VaultDeposit" to TxCategory.VAULT,
            "LoanBrokerSet" to TxCategory.VAULT,
            "XChainCommit" to TxCategory.BRIDGE,
            "CheckCash" to TxCategory.CHECK,
            "Clawback" to TxCategory.TRUST,
            "AMMClawback" to TxCategory.AMM,
            "NFTokenModify" to TxCategory.NFT,
            "Batch" to TxCategory.ACCOUNT,
            "EnableAmendment" to TxCategory.NETWORK,
            "SomethingFromTheFuture" to TxCategory.OTHER,
        ).forEach { (type, category) -> assertEquals(type, category, TxCategory.of(type)) }
    }

    @Test
    fun dashboardCountsExactTypes() {
        val stats = DashboardStats()
        listOf("OracleSet", "OracleSet", "Payment").forEach {
            stats.onEvent(StreamEvent.Transaction(StreamTx("H$it", it, TxCategory.of(it), "rA", null, null, 12, "tesSUCCESS", 1)))
        }
        assertEquals(listOf("OracleSet" to 2, "Payment" to 1), stats.snapshot().typeCounts)
    }

    @Test
    fun escrowsChecksTagsMemosAndSenders() {
        val create = tx(
            """{"type":"transaction","ledger_index":5,"meta":{"TransactionResult":"tesSUCCESS"},
               "transaction":{"TransactionType":"EscrowCreate","Account":"rBusy","Destination":"rB","Amount":"7000000","Fee":"12"}}""",
        )
        val finish = tx(
            """{"type":"transaction","ledger_index":5,
               "meta":{"TransactionResult":"tesSUCCESS","AffectedNodes":[
                 {"DeletedNode":{"LedgerEntryType":"Escrow","FinalFields":{"Amount":"7000000","Account":"rBusy"}}}]},
               "transaction":{"TransactionType":"EscrowFinish","Account":"rBusy","Owner":"rBusy","OfferSequence":1,"Fee":"12"}}""",
        )
        val tagged = tx(
            """{"type":"transaction","ledger_index":5,"meta":{"TransactionResult":"tesSUCCESS"},
               "transaction":{"TransactionType":"Payment","Account":"rOther","Destination":"rEx","DestinationTag":42,
                 "Amount":"1000000","Fee":"12","Memos":[{"Memo":{"MemoType":"746578742F706C61696E","MemoData":"6869"}},{"Memo":{"MemoData":"00"}}]}}""",
        )
        val check = tx(
            """{"type":"transaction","ledger_index":5,"meta":{"TransactionResult":"tesSUCCESS"},
               "transaction":{"TransactionType":"CheckCash","Account":"rOther","CheckID":"AB","Fee":"12"}}""",
        )
        assertEquals(7_000_000L, finish.effects.escrowReleasedDrops)
        assertEquals(listOf("text/plain", "untyped"), tagged.effects.memoTypes)

        val a = ActivityTally().apply { listOf(create, finish, create, tagged, check).forEach(::add) }.snapshot()
        assertEquals(2L, a.escrowsCreated)
        assertEquals(1L, a.escrowsFinished)
        assertEquals(14_000_000L, a.escrowLockedDrops)
        assertEquals(7_000_000L, a.escrowReleasedDrops)
        assertEquals(1L, a.checksCashed)
        assertEquals(1L, a.payments)
        assertEquals(1L, a.paymentsWithTag)
        assertEquals(1L, a.withMemos)
        assertEquals(mapOf("text/plain" to 1L, "untyped" to 1L), a.topMemoTypes.toMap())
        assertEquals("rBusy" to 3L, a.topAccounts.first())
        assertEquals(5L, a.transactions)
    }

    @Test
    fun newAccountsSlicedByDay() {
        fun created(n: Int) = StreamTx("H", "Payment", TxCategory.PAYMENT, "rA", "rB", Amount.Xrp(1), 12, "tesSUCCESS", 1,
            effects = TxEffects(accountsCreated = n))
        val from = LedgerHeader(0, 0, 0)
        val day = 86_400L
        // One sample per day, the third day busiest.
        val samples = (0 until 7).map { d ->
            SampledLedger(LedgerHeader(d.toLong() + 1, d * day + 100, 0), List(if (d == 2) 3 else 1) { created(1) })
        }
        val series = LedgerHistory.accountSeries(StatsPeriod.WEEK, from, samples, ledgers = 7_000, span = 7 * day)
        assertEquals(7, series.size)
        assertEquals(1_000L, series[0])
        assertEquals(3_000L, series[2])
    }
}
