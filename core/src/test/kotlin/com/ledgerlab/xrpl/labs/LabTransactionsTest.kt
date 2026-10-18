package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.wallet.Autofill
import com.ledgerlab.xrpl.wallet.WalletKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class LabTransactionsTest {
    private val fill = Autofill(sequence = 5, feeDrops = 12, lastLedgerSequence = 100)
    private val other = "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"

    @Test
    fun encodesCurrencyCodes() {
        assertEquals("USD", Currencies.encode("USD"))
        assertEquals("4C41425300000000000000000000000000000000", Currencies.encode("LABS"))
        assertFalse(Currencies.isValid("XRP"))
        assertTrue(Currencies.isValid("LAB"))
    }

    @Test
    fun everyLabTransactionSigns() {
        val wallet = WalletKeys.generate()
        val token = Asset.Token("LAB", other)
        val builders = listOf<(org.xrpl.xrpl4j.model.transactions.Address, org.xrpl.xrpl4j.crypto.keys.PublicKey) -> org.xrpl.xrpl4j.model.transactions.Transaction>(
            { a, k -> LabTransactions.enableRippling(a, k, fill) },
            { a, k -> LabTransactions.trustSet(a, k, fill, token, BigDecimal("1000000")) },
            { a, k -> LabTransactions.payment(a, k, fill, other, token.amount(BigDecimal("25.5"))) },
            { a, k -> LabTransactions.offerCreate(a, k, fill, Asset.Xrp.amount(BigDecimal("10")), token.amount(BigDecimal("5"))) },
            { a, k -> LabTransactions.offerCancel(a, k, fill, 4) },
            { a, k -> LabTransactions.nftMint(a, k, fill, "https://example.com/nft.json", 7, transferable = true) },
            { a, k -> LabTransactions.escrowCreate(a, k, fill, other, 1_000_000, 800_000_000, 800_000_600) },
            { a, k -> LabTransactions.escrowFinish(a, k, fill, other, 3) },
            { a, k -> LabTransactions.ammCreate(a, k, fill, Asset.Xrp.amount(BigDecimal("100")), token.amount(BigDecimal("50")), 500, 200_000) },
            { a, k -> LabTransactions.ammDeposit(a, k, fill, Asset.Xrp, token, Asset.Xrp.amount(BigDecimal("1")), token.amount(BigDecimal("0.5"))) },
        )
        builders.forEach { build ->
            val signed = WalletKeys.sign(wallet.secret, build)
            assertEquals(64, signed.hash.length)
        }
    }
}
