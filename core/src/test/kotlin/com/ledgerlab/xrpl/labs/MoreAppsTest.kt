package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.wallet.Autofill
import com.ledgerlab.xrpl.wallet.WalletKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec
import org.xrpl.xrpl4j.model.transactions.AccountSet
import java.math.BigDecimal

class MoreAppsTest {
    private val fill = Autofill(sequence = 5, feeDrops = 12, lastLedgerSequence = 100)
    private val other = "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"
    private val checkId = "5BD5B5A1B4A9F4E1C9B9E1F0D7E0C2B8A3A7E6F1D2C3B4A5968778695A4B3C2D"

    @Test
    fun everyNewTransactionSigns() {
        val wallet = WalletKeys.generate()
        val builders = listOf<(org.xrpl.xrpl4j.model.transactions.Address, org.xrpl.xrpl4j.crypto.keys.PublicKey) -> org.xrpl.xrpl4j.model.transactions.Transaction>(
            { a, k -> LabTransactions.accountSetFlag(a, k, fill, AccountSet.AccountSetFlag.ALLOW_TRUSTLINE_CLAWBACK) },
            { a, k -> LabTransactions.accountSetFlag(a, k, fill, AccountSet.AccountSetFlag.REQUIRE_AUTH) },
            { a, k -> LabTransactions.authorizeHolder(a, k, fill, other, "PTS") },
            { a, k -> LabTransactions.clawback(a, k, fill, other, "PTS", BigDecimal("15")) },
            { a, k -> LabTransactions.checkCreate(a, k, fill, other, Asset.Xrp.amount(BigDecimal("2")), 800_000_600, LedgerText.idFor("sub:Streaming")) },
            { a, k -> LabTransactions.checkCash(a, k, fill, checkId, Asset.Xrp.amount(BigDecimal("2"))) },
            { a, k -> LabTransactions.checkCancel(a, k, fill, checkId) },
            { a, k -> LabTransactions.signerListSet(a, k, fill, 2, listOf(other, WalletKeys.generate().address, WalletKeys.generate().address)) },
            { a, k -> LabTransactions.credentialCreate(a, k, fill, other, "KYC", 800_000_600, "https://example.com/kyc") },
            { a, k -> LabTransactions.credentialAccept(a, k, fill, other, "KYC") },
            { a, k -> LabTransactions.credentialDelete(a, k, fill, "KYC", subject = other) },
        )
        builders.forEach { assertEquals(64, WalletKeys.sign(wallet.secret, it).hash.length) }
    }

    @Test
    fun clawbackNamesTheHolderAsTheAmountIssuer() {
        val wallet = WalletKeys.generate()
        var issuerField = ""
        WalletKeys.sign(wallet.secret) { a, k ->
            LabTransactions.clawback(a, k, fill, other, "PTS", BigDecimal.TEN).also {
                issuerField = (it.amount() as org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount).issuer().value()
            }
        }
        assertEquals(other, issuerField)
    }

    @Test
    fun twoOfThreeSignaturesAssembleIntoOneTransaction() {
        val treasury = WalletKeys.generate()
        val signers = List(3) { WalletKeys.generate() }
        val tx = LabTransactions.multiSigPayment(treasury.address, other, 1_000_000, sequence = 7, baseFeeDrops = 10, signers = 2, lastLedgerSequence = 500, memo = "rent")
        assertEquals(30L, tx.fee().value().toLong())
        val shares = signers.take(2).map { WalletKeys.multiSign(it.secret, tx) }
        assertEquals(signers[0].address, shares[0].address)
        val signed = WalletKeys.assembleMultiSigned(tx, shares)
        assertEquals(64, signed.hash.length)
        val json = XrplBinaryCodec.getInstance().decode(signed.blob)
        assertTrue(json.contains("\"Signers\""))
        assertTrue(json.contains("\"SigningPubKey\":\"\""))
        signers.take(2).forEach { assertTrue(json.contains(it.address)) }
    }

    @Test
    fun ledgerTextRoundTrips() {
        assertEquals("Over 18", LedgerText.decode(LedgerText.encode("Over 18")))
        assertEquals(64, LedgerText.idFor("sub:News").length)
        val flags = AccountFlags(0x8004_0000L)
        assertTrue(flags.requireAuth && flags.allowClawback && !flags.defaultRipple)
    }
}
