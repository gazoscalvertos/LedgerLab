package com.ledgerlab.xrpl

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodesTest {
    private fun obj(text: String) = StreamParser.json.parseToJsonElement(text) as JsonObject

    private fun encode(major: Long, minor: Long, patch: Long, tag: Long): String =
        ((0x183BL shl 48) or (major shl 40) or (minor shl 32) or (patch shl 24) or (tag shl 16)).toString()

    @Test
    fun decodesRippledVersions() {
        assertEquals("2.3.1", StreamParser.decodeServerVersion(encode(2, 3, 1, 0xC0)))
        assertEquals("2.4.0-rc2", StreamParser.decodeServerVersion(encode(2, 4, 0, 0x82)))
        assertEquals("1.12.0-b5", StreamParser.decodeServerVersion(encode(1, 12, 0, 0x45)))
        assertEquals("other", StreamParser.decodeServerVersion((1L shl 48).toString()))
        assertNull(StreamParser.decodeServerVersion("nope"))
    }

    @Test
    fun validationsCarryVersionsIntoTheSnapshot() {
        val event = StreamParser.parse(
            """{"type":"validationReceived","validation_public_key":"n9A","ledger_index":"256","ledger_hash":"H",
               "server_version":"${encode(2, 3, 0, 0xC0)}"}""",
        ) as StreamEvent.ValidationReceived
        assertEquals("2.3.0", event.validation.version)
        val tracker = ValidatorTracker()
        // Without a published list, a validator needs a few validations before it's shown.
        for (key in 0 until 3) {
            repeat(3) { i ->
                tracker.onValidation(event.validation.copy(signingKey = "n9V$key", ledgerIndex = 256L + i, version = if (key < 2) "2.3.0" else "2.2.1"))
            }
        }
        assertEquals(listOf("2.3.0" to 2, "2.2.1" to 1), tracker.snapshot().versions)
    }

    @Test
    fun amendmentStagesSortMajorityFirstAndEnabledLast() {
        val tracker = ValidatorTracker()
        tracker.setAmendments(listOf(
            AmendmentStatus("A", "Done", enabled = true, votes = null, threshold = null),
            AmendmentStatus("B", "Quiet", enabled = false, votes = 0, threshold = 28),
            AmendmentStatus("C", "Close", enabled = false, votes = 30, threshold = 28, majoritySince = 1),
            AmendmentStatus("D", "Busy", enabled = false, votes = 12, threshold = 28),
        ))
        val status = tracker.snapshot().amendmentStatus
        assertEquals(listOf("Close", "Busy", "Quiet", "Done"), status.map { it.name })
        assertEquals(AmendmentStage.MAJORITY, status.first().stage)
    }

    @Test
    fun readsServerInfo() {
        val base = Nodes.parseServerInfo("s1", obj(
            """{"info":{"build_version":"2.3.0","server_state":"full","uptime":3600,"peers":3,"load_factor":1,
               "complete_ledgers":"32570-90000000"}}""",
        ))
        assertEquals("full", base.serverState)
        assertEquals(1.0, base.loadFactor!!, 1e-9)
        assertEquals(3, base.peers)
        assertEquals("2.3.0", base.buildVersion)
    }
}
