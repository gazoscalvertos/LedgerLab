package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.wallet.Autofill
import com.ledgerlab.xrpl.wallet.WalletKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NameRecordsTest {
    private fun op(json: String, ledger: Long) = Triple(Json.parseToJsonElement(json) as JsonObject, "rOwner", "H$ledger" to ledger)

    @Test
    fun hostsSitUnderTheName() {
        assertEquals("alice.xrp", NameRecords.hostName("@", "alice.xrp"))
        assertEquals("alice.xrp", NameRecords.hostName("", "alice.xrp"))
        assertEquals("www.alice.xrp", NameRecords.hostName("WWW", "alice.xrp"))
        assertEquals("api.v2.alice.xrp", NameRecords.hostName("api.v2", "alice.xrp"))
        assertNull(NameRecords.hostName("bad_host", "alice.xrp"))
    }

    @Test
    fun recordValuesAreChecked() {
        assertNull(RecordType.A.check("203.0.113.7"))
        assertNotNull(RecordType.A.check("300.1.1.1"))
        assertNotNull(RecordType.A.check("example.com"))
        assertNull(RecordType.AAAA.check("2001:db8::7"))
        assertNotNull(RecordType.AAAA.check("203.0.113.7"))
        assertNull(RecordType.CNAME.check("pay.alice.xrp"))
        assertNull(RecordType.TXT.check("site-verification=abc123"))
    }

    @Test
    fun replayAppliesSetsAndDeletesInOrder() {
        val zone = NameRecords.replay(
            listOf(
                op(NameRecords.setMemo("alice.xrp", RecordType.A, "203.0.113.7"), 1),
                op(NameRecords.setMemo("alice.xrp", RecordType.A, "203.0.113.8"), 2),
                op(NameRecords.setMemo("alice.xrp", RecordType.TXT, "one"), 3),
                op(NameRecords.setMemo("alice.xrp", RecordType.TXT, "two"), 4),
                op(NameRecords.deleteMemo("alice.xrp", RecordType.TXT, "one"), 5),
                op(NameRecords.setMemo("www.alice.xrp", RecordType.CNAME, "alice.xrp"), 6),
                op(NameRecords.setMemo("alice.xrp", RecordType.A, "999.0.0.1"), 7),
            ),
        )
        assertEquals(listOf("A 203.0.113.8", "TXT two", "CNAME alice.xrp"), zone.map { "${it.type} ${it.value}" })
        assertEquals(2L, zone.first().ledgerIndex)
    }

    @Test
    fun recordMemoSigns() {
        val wallet = WalletKeys.generate()
        val fill = Autofill(sequence = 5, feeDrops = 12, lastLedgerSequence = 100)
        val signed = WalletKeys.sign(wallet.secret) { a, k -> LabTransactions.nameRecord(a, k, fill, NameRecords.setMemo("alice.xrp", RecordType.A, "203.0.113.7")) }
        assertEquals(64, signed.hash.length)
    }
}
