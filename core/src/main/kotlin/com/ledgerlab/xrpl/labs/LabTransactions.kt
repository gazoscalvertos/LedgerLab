package com.ledgerlab.xrpl.labs

import com.google.common.primitives.UnsignedInteger
import com.google.common.primitives.UnsignedLong
import com.ledgerlab.xrpl.wallet.Autofill
import org.xrpl.xrpl4j.crypto.keys.PublicKey
import org.xrpl.xrpl4j.model.flags.AmmDepositFlags
import org.xrpl.xrpl4j.model.flags.NfTokenMintFlags
import org.xrpl.xrpl4j.model.flags.OfferCreateFlags
import org.xrpl.xrpl4j.model.flags.TrustSetFlags
import org.xrpl.xrpl4j.model.ledger.Issue
import org.xrpl.xrpl4j.model.transactions.AccountSet
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.AmmCreate
import org.xrpl.xrpl4j.model.transactions.AmmDeposit
import org.xrpl.xrpl4j.model.transactions.CurrencyAmount
import org.xrpl.xrpl4j.model.transactions.EscrowCreate
import org.xrpl.xrpl4j.model.transactions.EscrowFinish
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount
import org.xrpl.xrpl4j.model.transactions.NfTokenBurn
import org.xrpl.xrpl4j.model.transactions.NfTokenId
import org.xrpl.xrpl4j.model.transactions.NfTokenMint
import org.xrpl.xrpl4j.model.transactions.NfTokenUri
import org.xrpl.xrpl4j.model.transactions.OfferCancel
import org.xrpl.xrpl4j.model.transactions.OfferCreate
import org.xrpl.xrpl4j.model.transactions.Payment
import org.xrpl.xrpl4j.model.transactions.TradingFee
import org.xrpl.xrpl4j.model.transactions.TrustSet
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount
import java.math.BigDecimal

/** An asset on the ledger: XRP, or a token identified by currency code and issuer. */
sealed interface Asset {
    data object Xrp : Asset

    data class Token(val currency: String, val issuer: String) : Asset

    fun issue(): Issue = when (this) {
        Xrp -> Issue.XRP
        is Token -> Issue.builder().currency(Currencies.encode(currency)).issuer(Address.of(issuer)).build()
    }

    /** [value] is in XRP for [Xrp] and in token units otherwise. */
    fun amount(value: BigDecimal): CurrencyAmount = when (this) {
        Xrp -> XrpCurrencyAmount.ofDrops(value.movePointRight(6).toBigInteger().toLong())
        is Token -> IssuedCurrencyAmount.builder()
            .currency(Currencies.encode(currency))
            .issuer(Address.of(issuer))
            .value(value.stripTrailingZeros().toPlainString())
            .build()
    }

    val label: String get() = if (this is Token) currency else "XRP"
}

object Currencies {
    /**
     * Three-character codes go on the ledger as-is (except "XRP", which is reserved). Longer names are
     * stored as 40 hex characters: the ASCII bytes, zero padded.
     */
    fun encode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.length == 3) return trimmed
        require(trimmed.length in 4..20) { "Token codes are 3 characters, or 4 to 20 for a long name." }
        return trimmed.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) }.padEnd(40, '0')
    }

    fun isValid(code: String): Boolean {
        val t = code.trim()
        if (t.equals("XRP", ignoreCase = true)) return false
        if (t.length < 3 || t.length > 20) return false
        return t.all { it.isLetterOrDigit() || it in "?!@#$%^&*<>(){}[]|" }
    }
}

/** Builders for every transaction the labs send. Each takes the signer, its key and the autofilled fields. */
object LabTransactions {
    fun enableRippling(from: Address, key: PublicKey, fill: Autofill): AccountSet = AccountSet.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .setFlag(AccountSet.AccountSetFlag.DEFAULT_RIPPLE)
        .build()

    fun trustSet(from: Address, key: PublicKey, fill: Autofill, token: Asset.Token, limit: BigDecimal): TrustSet =
        TrustSet.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .limitAmount(token.amount(limit) as IssuedCurrencyAmount)
            .flags(TrustSetFlags.builder().tfSetNoRipple().build())
            .build()

    fun payment(from: Address, key: PublicKey, fill: Autofill, destination: String, amount: CurrencyAmount): Payment =
        Payment.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .destination(Address.of(destination))
            .amount(amount)
            .build()

    /** An offer to give [gets] in exchange for [pays]. */
    fun offerCreate(from: Address, key: PublicKey, fill: Autofill, gets: CurrencyAmount, pays: CurrencyAmount): OfferCreate =
        OfferCreate.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .takerGets(gets)
            .takerPays(pays)
            .flags(OfferCreateFlags.empty())
            .build()

    fun offerCancel(from: Address, key: PublicKey, fill: Autofill, offerSequence: Long): OfferCancel = OfferCancel.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .offerSequence(UnsignedInteger.valueOf(offerSequence))
        .build()

    fun nftMint(from: Address, key: PublicKey, fill: Autofill, uri: String, taxon: Long, transferable: Boolean): NfTokenMint =
        NfTokenMint.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .tokenTaxon(UnsignedLong.valueOf(taxon))
            .apply { if (uri.isNotBlank()) uri(NfTokenUri.of(hex(uri.trim()))) }
            .flags(NfTokenMintFlags.builder().tfBurnable(true).tfTransferable(transferable).build())
            .build()

    fun nftBurn(from: Address, key: PublicKey, fill: Autofill, nftId: String): NfTokenBurn = NfTokenBurn.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .nfTokenId(NfTokenId.of(nftId))
        .build()

    /** Times are seconds since the Ripple epoch. */
    fun escrowCreate(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        destination: String,
        drops: Long,
        finishAfter: Long,
        cancelAfter: Long?,
    ): EscrowCreate = EscrowCreate.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .destination(Address.of(destination))
        .amount(XrpCurrencyAmount.ofDrops(drops))
        .finishAfter(UnsignedLong.valueOf(finishAfter))
        .apply { if (cancelAfter != null) cancelAfter(UnsignedLong.valueOf(cancelAfter)) }
        .build()

    fun escrowFinish(from: Address, key: PublicKey, fill: Autofill, owner: String, offerSequence: Long): EscrowFinish =
        EscrowFinish.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .owner(Address.of(owner))
            .offerSequence(UnsignedInteger.valueOf(offerSequence))
            .build()

    /** [tradingFee] is in units of 1/100,000 (so 500 = 0.5%). AMMCreate costs one owner reserve as its fee. */
    fun ammCreate(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        amount1: CurrencyAmount,
        amount2: CurrencyAmount,
        tradingFee: Int,
        createFeeDrops: Long,
    ): AmmCreate = AmmCreate.builder()
        .account(from).signingPublicKey(key).fee(XrpCurrencyAmount.ofDrops(createFeeDrops))
        .sequence(seq(fill)).lastLedgerSequence(last(fill))
        .amount(amount1)
        .amount2(amount2)
        .tradingFee(TradingFee.of(UnsignedInteger.valueOf(tradingFee.toLong())))
        .build()

    fun ammDeposit(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        asset1: Asset,
        asset2: Asset,
        amount1: CurrencyAmount,
        amount2: CurrencyAmount,
    ): AmmDeposit = AmmDeposit.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .asset(asset1.issue())
        .asset2(asset2.issue())
        .amount(amount1)
        .amount2(amount2)
        .flags(AmmDepositFlags.TWO_ASSET)
        .build()

    internal fun hex(text: String): String = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }

    private fun fee(fill: Autofill) = XrpCurrencyAmount.ofDrops(fill.feeDrops)
    private fun seq(fill: Autofill) = UnsignedInteger.valueOf(fill.sequence)
    private fun last(fill: Autofill) = UnsignedInteger.valueOf(fill.lastLedgerSequence)
}
