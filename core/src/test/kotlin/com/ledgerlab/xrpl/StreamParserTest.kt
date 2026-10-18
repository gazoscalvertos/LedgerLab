package com.ledgerlab.xrpl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParserTest {
    @Test
    fun parsesLedgerClosed() {
        val event = StreamParser.parse(
            """{"type":"ledgerClosed","fee_base":10,"ledger_index":90000001,"ledger_time":782000000,
               "reserve_base":1000000,"reserve_inc":200000,"txn_count":42,"validated_ledgers":"1-90000001"}""",
            receivedAtMs = 5_000,
        ) as StreamEvent.LedgerClosed
        with(event.ledger) {
            assertEquals(90000001L, index)
            assertEquals(42, txnCount)
            assertEquals(10L, feeBaseDrops)
            assertEquals(1_000_000L, reserveBaseDrops)
            assertEquals(5_000L, receivedAtMs)
        }
    }

    @Test
    fun parsesV1PaymentWithDeliveredAmount() {
        val event = StreamParser.parse(
            """{"type":"transaction","engine_result":"tesSUCCESS","ledger_index":7,"validated":true,
               "meta":{"TransactionResult":"tesSUCCESS","delivered_amount":"2500000"},
               "transaction":{"TransactionType":"Payment","Account":"rSender","Destination":"rDest",
                 "Amount":"9999999","Fee":"12","hash":"ABC123"}}""",
        ) as StreamEvent.Transaction
        with(event.tx) {
            assertEquals("ABC123", hash)
            assertEquals(TxCategory.PAYMENT, category)
            assertEquals(2_500_000L, xrpDrops)
            assertEquals(12L, feeDrops)
            assertEquals("rDest", destination)
            assertTrue(succeeded)
        }
    }

    @Test
    fun parsesV2OfferWithIssuedAmount() {
        val event = StreamParser.parse(
            """{"type":"transaction","hash":"DEF456","ledger_index":8,
               "meta":{"TransactionResult":"tecKILLED"},
               "tx_json":{"TransactionType":"OfferCreate","Account":"rTrader","Fee":"10",
                 "TakerGets":{"currency":"USD","issuer":"rIssuer","value":"125.5"},"TakerPays":"1000"}}""",
        ) as StreamEvent.Transaction
        with(event.tx) {
            assertEquals("DEF456", hash)
            assertEquals(TxCategory.DEX, category)
            assertEquals(Amount.Issued("USD", "125.5", "rIssuer"), amount)
            assertEquals(0L, xrpDrops)
            assertEquals(false, succeeded)
        }
    }

    @Test
    fun parsesSubscribeResponse() {
        val event = StreamParser.parse(
            """{"id":"ledgerlab","status":"success","type":"response",
               "result":{"fee_base":10,"ledger_index":123,"reserve_base":1000000,"reserve_inc":200000}}""",
        )
        assertEquals(StreamEvent.Subscribed(123, 10, 1_000_000, 200_000), event)
    }

    @Test
    fun ignoresGarbage() {
        assertNull(StreamParser.parse("not json"))
        assertNull(StreamParser.parse("""{"type":"serverStatus"}"""))
        assertNull(StreamParser.parse("[]"))
    }

    @Test
    fun decodesHexCurrencyCodes() {
        assertEquals("SOLO", StreamParser.decodeCurrency("534F4C4F00000000000000000000000000000000"))
        assertEquals("USD", StreamParser.decodeCurrency("USD"))
    }
}
