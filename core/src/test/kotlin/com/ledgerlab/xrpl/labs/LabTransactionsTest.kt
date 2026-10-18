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
    private val nftId = "000800006203F49C21D5D6E022CB16DE3538F248662FC73C00000E9900000000"
    private val offerId = "5BD5B5A1B4A9F4E1C9B9E1F0D7E0C2B8A3A7E6F1D2C3B4A5968778695A4B3C2D"

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
            { a, k -> LabTransactions.nftMint(a, k, fill, "ipfs://bafy", 1, transferable = true, transferFee = 2_500, onlyXrp = true) },
            { a, k -> LabTransactions.escrowCreate(a, k, fill, other, Asset.Xrp.amount(BigDecimal.ONE), 800_000_000, 800_000_600) },
            { a, k -> LabTransactions.escrowCreate(a, k, fill, other, token.amount(BigDecimal("12")), 800_000_000, 800_000_600) },
            { a, k -> LabTransactions.escrowCancel(a, k, fill, other, 3) },
            { a, k -> LabTransactions.allowTrustLineLocking(a, k, fill) },
            { a, k -> LabTransactions.nftCreateOffer(a, k, fill, nftId, Asset.Xrp.amount(BigDecimal("5")), sell = true) },
            { a, k -> LabTransactions.nftCreateOffer(a, k, fill, nftId, token.amount(BigDecimal("5")), sell = false, owner = other) },
            { a, k -> LabTransactions.nftAcceptOffer(a, k, fill, sellOffer = offerId) },
            { a, k -> LabTransactions.nftAcceptOffer(a, k, fill, buyOffer = offerId) },
            { a, k -> LabTransactions.nftCancelOffer(a, k, fill, listOf(offerId)) },
            { a, k -> LabTransactions.channelCreate(a, k, fill, other, 10_000_000, 60) },
            { a, k -> LabTransactions.channelFund(a, k, fill, offerId, 1_000_000) },
            { a, k -> LabTransactions.channelClaim(a, k, fill, offerId, 500_000, "AB".repeat(64), "ED" + "00".repeat(32)) },
            { a, k -> LabTransactions.channelClaim(a, k, fill, offerId, close = true) },
            { a, k -> LabTransactions.escrowFinish(a, k, fill, other, 3) },
            { a, k -> LabTransactions.ammCreate(a, k, fill, Asset.Xrp.amount(BigDecimal("100")), token.amount(BigDecimal("50")), 500, 200_000) },
            { a, k -> LabTransactions.ammDeposit(a, k, fill, Asset.Xrp, token, Asset.Xrp.amount(BigDecimal("1")), token.amount(BigDecimal("0.5"))) },
        )
        builders.forEach { build ->
            val signed = WalletKeys.sign(wallet.secret, build)
            assertEquals(64, signed.hash.length)
        }
    }

    @Test
    fun channelClaimSignatureVerifies() {
        val wallet = WalletKeys.generate()
        val channel = offerId
        val drops = 1_234_567L
        val signature = WalletKeys.signClaim(wallet.secret, channel, drops)

        // The claim is "CLM\0" + channel ID + amount as a big-endian uint64, signed with the payer's key.
        val message = byteArrayOf(0x43, 0x4C, 0x4D, 0x00) + hex(channel) +
            java.nio.ByteBuffer.allocate(8).putLong(drops).array()
        val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
        verifier.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(hex(wallet.publicKey.drop(2)), 0))
        verifier.update(message, 0, message.size)
        assertTrue(verifier.verifySignature(hex(signature)))

        // A claim for a different amount must not verify with the same signature.
        val other = byteArrayOf(0x43, 0x4C, 0x4D, 0x00) + hex(channel) + java.nio.ByteBuffer.allocate(8).putLong(drops + 1).array()
        verifier.update(other, 0, other.size)
        assertFalse(verifier.verifySignature(hex(signature)))
    }

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
