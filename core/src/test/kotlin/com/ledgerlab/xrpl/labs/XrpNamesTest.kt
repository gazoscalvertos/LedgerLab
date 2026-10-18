package com.ledgerlab.xrpl.labs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrpNamesTest {
    @Test
    fun normalizesNames() {
        assertEquals("alice.xrp", XrpNames.normalize("Alice"))
        assertEquals("alice.xrp", XrpNames.normalize(" alice.XRP "))
        assertEquals("pay.alice.xrp", XrpNames.normalize("pay.alice"))
        assertNull(XrpNames.normalize("-bad"))
        assertNull(XrpNames.normalize("a.b.c.xrp"))
        assertNull(XrpNames.normalize("sp ace"))
        assertNull(XrpNames.normalize(""))
    }

    @Test
    fun parentsAndUris() {
        assertTrue(XrpNames.isSubdomain("pay.alice.xrp"))
        assertFalse(XrpNames.isSubdomain("alice.xrp"))
        assertEquals("alice.xrp", XrpNames.parentOf("pay.alice.xrp"))
        assertNull(XrpNames.parentOf("alice.xrp"))
        assertEquals("pay.alice.xrp", XrpNames.nameFromUri(XrpNames.uriFor("pay.alice.xrp")))
        assertNull(XrpNames.nameFromUri("ipfs://bafy"))
    }
}
