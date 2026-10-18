package com.ledgerlab.xrpl.wallet

import com.google.common.primitives.UnsignedInteger
import org.xrpl.xrpl4j.crypto.keys.PublicKey
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.Payment
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount
import java.math.BigDecimal

object Transactions {
    /** Max XRP supply, used to reject silly inputs before they reach the network. */
    private val MAX_XRP = BigDecimal("100000000000")

    /** "12.5" -> 12_500_000 drops. Null for anything that isn't a positive amount with at most 6 decimals. */
    fun parseXrp(text: String): Long? {
        val value = text.trim().replace(",", "").toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0 || value > MAX_XRP || value.stripTrailingZeros().scale() > 6) return null
        return value.movePointRight(6).longValueExact()
    }

    fun xrpPayment(
        from: Address,
        publicKey: PublicKey,
        destination: String,
        drops: Long,
        destinationTag: Long?,
        fill: Autofill,
    ): Payment = Payment.builder()
        .account(from)
        .destination(Address.of(destination.trim()))
        .amount(XrpCurrencyAmount.ofDrops(drops))
        .apply { if (destinationTag != null) destinationTag(UnsignedInteger.valueOf(destinationTag)) }
        .fee(XrpCurrencyAmount.ofDrops(fill.feeDrops))
        .sequence(UnsignedInteger.valueOf(fill.sequence))
        .lastLedgerSequence(UnsignedInteger.valueOf(fill.lastLedgerSequence))
        .signingPublicKey(publicKey)
        .build()
}
