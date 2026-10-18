package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.wallet.Autofill
import com.ledgerlab.xrpl.wallet.WalletKeys
import com.ripple.cryptoconditions.CryptoConditionReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AppKitsTest {
    private val fill = Autofill(sequence = 5, feeDrops = 12, lastLedgerSequence = 100)
    private val other = "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"

    @Test
    fun hashesFilesLikeSha256sum() {
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", Notary.sha256Hex("abc".toByteArray()))
    }

    @Test
    fun notarisationSignsWithTheHashInAMemo() {
        val wallet = WalletKeys.generate()
        val hash = Notary.sha256Hex("contract v1".toByteArray())
        val tx = LabTransactions.notarize(org.xrpl.xrpl4j.model.transactions.Address.of(wallet.address), org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(wallet.publicKey), fill, hash, "contract.pdf")
        val memo = tx.memos().single().memo()
        assertEquals(hash, memo.memoData().get())
        assertEquals(LabTransactions.hex(Notary.MEMO_TYPE), memo.memoType().get())
        assertEquals(64, WalletKeys.sign(wallet.secret) { a, k -> LabTransactions.notarize(a, k, fill, hash, "contract.pdf") }.hash.length)
    }

    @Test
    fun locksAreFreshAndTheirFulfillmentMatches() {
        val a = EscrowLocks.newLock()
        val b = EscrowLocks.newLock()
        assertNotEquals(a.conditionHex, b.conditionHex)
        val fulfillment = CryptoConditionReader.readFulfillment(LabTransactions.unhex(a.fulfillmentHex))
        val condition = CryptoConditionReader.readCondition(LabTransactions.unhex(a.conditionHex))
        assertEquals(condition, fulfillment.derivedCondition)
        assertTrue(fulfillment.verify(condition, ByteArray(0)))
    }

    @Test
    fun conditionalEscrowSignsAndFinishCostsMore() {
        val wallet = WalletKeys.generate()
        val lock = EscrowLocks.newLock()
        val create = WalletKeys.sign(wallet.secret) { a, k ->
            LabTransactions.escrowCreateConditional(a, k, fill, other, Asset.Xrp.amount(BigDecimal("25")), lock.conditionHex, 800_000_600, memo = "Logo design")
        }
        assertEquals(64, create.hash.length)
        var finishFee = 0L
        val finish = WalletKeys.sign(wallet.secret) { a, k ->
            LabTransactions.escrowFinishWithFulfillment(a, k, fill, other, 5, lock.conditionHex, lock.fulfillmentHex)
                .also { finishFee = it.fee().value().toLong() }
        }
        assertEquals(64, finish.hash.length)
        // Base fee x (33 + ceil(36 bytes / 16)) for a 32-byte preimage fulfillment: 12 x 35.
        assertEquals(420L, finishFee)
    }

    @Test
    fun organiserCanBurnAHoldersTicket() {
        val wallet = WalletKeys.generate()
        val nftId = "000800006203F49C21D5D6E022CB16DE3538F248662FC73C00000E9900000000"
        val signed = WalletKeys.sign(wallet.secret) { a, k -> LabTransactions.nftBurn(a, k, fill, nftId, owner = other).also { assertEquals(other, it.owner().get().value()) } }
        assertEquals(64, signed.hash.length)
    }

    @Test
    fun ticketUrisRoundTrip() {
        assertEquals("Neon Nights 2026" to 42, Tickets.parse(Tickets.uriFor("Neon Nights 2026", 42)))
        assertEquals("A#B" to 1, Tickets.parse(Tickets.uriFor("A#B", 1)))
        assertNull(Tickets.parse("xrpns:alice.xrp"))
        assertEquals(Tickets.taxonFor("Neon Nights"), Tickets.taxonFor("  neon nights "))
        assertTrue(Tickets.taxonFor("anything") in 0..0x7FFFFFFFL)
    }
}
