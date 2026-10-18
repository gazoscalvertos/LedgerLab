package com.ledgerlab.xrpl.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test


class WalletKeysTest {
    @Test
    fun importsTheGenesisSecret() {
        val info = WalletKeys.fromSecret("snoPBrXtMeMyMHUVTgbuqAfg1SUTb")
        assertEquals("rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh", info.address)
        assertEquals(KeyAlgorithm.SECP256K1, info.algorithm)
    }

    @Test
    fun generatedWalletsRoundTrip() {
        for (algorithm in KeyAlgorithm.entries) {
            val wallet = WalletKeys.generate(algorithm)
            assertEquals(algorithm, wallet.algorithm)
            assertEquals(wallet.address, WalletKeys.fromSecret(wallet.secret).address)
            assertTrue(WalletKeys.isValidAddress(wallet.address))
        }
    }

    @Test
    fun rejectsBadSecretsAndAddresses() {
        assertThrows(IllegalArgumentException::class.java) { WalletKeys.fromSecret("not a secret") }
        assertFalse(WalletKeys.isValidAddress("rNotAnAddress"))
    }

    @Test
    fun signsAPayment() {
        val wallet = WalletKeys.generate()
        val signed = WalletKeys.sign(wallet.secret) { from, key ->
            Transactions.xrpPayment(from, key, "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh", 1_000_000, 42, Autofill(7, 12, 100))
        }
        assertEquals(64, signed.hash.length)
        assertTrue(signed.blob.startsWith("1200"))
    }

    @Test
    fun parsesXrpAmounts() {
        assertEquals(12_500_000L, Transactions.parseXrp("12.5"))
        assertEquals(1L, Transactions.parseXrp("0.000001"))
        assertEquals(1_000_000_000L, Transactions.parseXrp("1,000"))
        assertNull(Transactions.parseXrp("0.0000001"))
        assertNull(Transactions.parseXrp("-1"))
        assertNull(Transactions.parseXrp("abc"))
    }
}
