package com.ledgerlab.xrpl.labs

import com.google.common.primitives.UnsignedInteger
import com.google.common.primitives.UnsignedLong
import com.ledgerlab.xrpl.wallet.Autofill
import com.ripple.cryptoconditions.CryptoConditionReader
import org.xrpl.xrpl4j.crypto.keys.PublicKey
import org.xrpl.xrpl4j.model.flags.AmmDepositFlags
import org.xrpl.xrpl4j.model.flags.NfTokenCreateOfferFlags
import org.xrpl.xrpl4j.model.flags.NfTokenMintFlags
import org.xrpl.xrpl4j.model.flags.PaymentChannelClaimFlags
import org.xrpl.xrpl4j.model.flags.OfferCreateFlags
import org.xrpl.xrpl4j.model.flags.TrustSetFlags
import org.xrpl.xrpl4j.model.ledger.Issue
import org.xrpl.xrpl4j.model.transactions.AccountSet
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.AmmCreate
import org.xrpl.xrpl4j.model.transactions.AmmDeposit
import org.xrpl.xrpl4j.model.transactions.CurrencyAmount
import org.xrpl.xrpl4j.model.transactions.EscrowCancel
import org.xrpl.xrpl4j.model.transactions.EscrowCreate
import org.xrpl.xrpl4j.model.transactions.EscrowFinish
import org.xrpl.xrpl4j.model.transactions.Hash256
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount
import org.xrpl.xrpl4j.model.transactions.Memo
import org.xrpl.xrpl4j.model.transactions.MemoWrapper
import org.xrpl.xrpl4j.model.transactions.NfTokenAcceptOffer
import org.xrpl.xrpl4j.model.transactions.NfTokenBurn
import org.xrpl.xrpl4j.model.transactions.NfTokenCancelOffer
import org.xrpl.xrpl4j.model.transactions.NfTokenCreateOffer
import org.xrpl.xrpl4j.model.transactions.NfTokenId
import org.xrpl.xrpl4j.model.transactions.NfTokenMint
import org.xrpl.xrpl4j.model.transactions.NfTokenUri
import org.xrpl.xrpl4j.model.transactions.OfferCancel
import org.xrpl.xrpl4j.model.transactions.OfferCreate
import org.xrpl.xrpl4j.model.transactions.Payment
import org.xrpl.xrpl4j.model.transactions.PaymentChannelClaim
import org.xrpl.xrpl4j.model.transactions.PaymentChannelCreate
import org.xrpl.xrpl4j.model.transactions.PaymentChannelFund
import org.xrpl.xrpl4j.model.transactions.TradingFee
import org.xrpl.xrpl4j.model.transactions.TransferFee
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

    /** Lets holders lock this issuer's tokens in escrow (XLS-85 token escrow, asfAllowTrustLineLocking). */
    fun allowTrustLineLocking(from: Address, key: PublicKey, fill: Autofill): AccountSet = AccountSet.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .setFlag(AccountSet.AccountSetFlag.ALLOW_TRUSTLINE_LOCKING)
        .build()

    fun trustSet(from: Address, key: PublicKey, fill: Autofill, token: Asset.Token, limit: BigDecimal): TrustSet =
        TrustSet.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .limitAmount(token.amount(limit) as IssuedCurrencyAmount)
            .flags(TrustSetFlags.builder().tfSetNoRipple().build())
            .build()

    fun payment(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        destination: String,
        amount: CurrencyAmount,
        memo: String? = null,
    ): Payment = Payment.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .destination(Address.of(destination))
        .amount(amount)
        .apply { if (memo != null) addMemos(MemoWrapper.builder().memo(Memo.withPlaintext(memo).build()).build()) }
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

    /**
     * [transferFee] is the issuer's royalty on secondary sales in units of 1/1000 of a percent
     * (0 to 50,000, so 2,500 = 2.5%). It needs the NFT to be transferable.
     */
    fun nftMint(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        uri: String,
        taxon: Long,
        transferable: Boolean,
        transferFee: Int = 0,
        onlyXrp: Boolean = false,
    ): NfTokenMint = NfTokenMint.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .tokenTaxon(UnsignedLong.valueOf(taxon))
        .apply { if (uri.isNotBlank()) uri(NfTokenUri.of(hex(uri.trim()))) }
        .apply { if (transferFee > 0) transferFee(TransferFee.of(UnsignedInteger.valueOf(transferFee.toLong()))) }
        .flags(NfTokenMintFlags.builder().tfBurnable(true).tfTransferable(transferable).tfOnlyXRP(onlyXrp).build())
        .build()

    /** [owner] is set when the issuer burns a burnable NFT someone else holds, like a ticket at the door. */
    fun nftBurn(from: Address, key: PublicKey, fill: Autofill, nftId: String, owner: String? = null): NfTokenBurn = NfTokenBurn.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .nfTokenId(NfTokenId.of(nftId))
        .apply { if (owner != null) owner(Address.of(owner)) }
        .build()

    /** Times are seconds since the Ripple epoch. [amount] can be XRP or, with token escrow (XLS-85), a token. */
    fun escrowCreate(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        destination: String,
        amount: CurrencyAmount,
        finishAfter: Long,
        cancelAfter: Long?,
    ): EscrowCreate = EscrowCreate.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .destination(Address.of(destination))
        .amount(amount)
        .finishAfter(UnsignedLong.valueOf(finishAfter))
        .apply { if (cancelAfter != null) cancelAfter(UnsignedLong.valueOf(cancelAfter)) }
        .build()

    /** An escrow released by a secret instead of (or as well as) a time: the PREIMAGE-SHA-256 [conditionHex]. */
    fun escrowCreateConditional(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        destination: String,
        amount: CurrencyAmount,
        conditionHex: String,
        cancelAfter: Long,
        memo: String? = null,
    ): EscrowCreate = EscrowCreate.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .destination(Address.of(destination))
        .amount(amount)
        .condition(CryptoConditionReader.readCondition(unhex(conditionHex)))
        .cancelAfter(UnsignedLong.valueOf(cancelAfter))
        .apply { if (memo != null) addMemos(MemoWrapper.builder().memo(Memo.withPlaintext(memo).build()).build()) }
        .build()

    /** Releases a conditional escrow by revealing the fulfillment. Costs more than a normal fee: 330 drops plus 10 per 16 bytes. */
    fun escrowFinishWithFulfillment(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        owner: String,
        offerSequence: Long,
        conditionHex: String,
        fulfillmentHex: String,
    ): EscrowFinish {
        val fulfillment = CryptoConditionReader.readFulfillment(unhex(fulfillmentHex))
        return EscrowFinish.builder()
            .account(from).signingPublicKey(key)
            .fee(EscrowFinish.computeFee(XrpCurrencyAmount.ofDrops(maxOf(fill.feeDrops, 10)), fulfillment))
            .sequence(seq(fill)).lastLedgerSequence(last(fill))
            .owner(Address.of(owner))
            .offerSequence(UnsignedInteger.valueOf(offerSequence))
            .condition(CryptoConditionReader.readCondition(unhex(conditionHex)))
            .fulfillment(fulfillment)
            .build()
    }

    /**
     * Records [sha256Hex] on the ledger in a memo. An AccountSet that changes nothing is the cheapest
     * transaction an account can send to itself, so the only cost is the fee.
     */
    fun notarize(from: Address, key: PublicKey, fill: Autofill, sha256Hex: String, label: String): AccountSet = AccountSet.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .addMemos(
            MemoWrapper.builder().memo(
                Memo.builder()
                    .memoType(hex(Notary.MEMO_TYPE))
                    .memoData(sha256Hex.uppercase())
                    .memoFormat(hex(label.take(120)))
                    .build(),
            ).build(),
        )
        .build()

    fun escrowCancel(from: Address, key: PublicKey, fill: Autofill, owner: String, offerSequence: Long): EscrowCancel =
        EscrowCancel.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .owner(Address.of(owner))
            .offerSequence(UnsignedInteger.valueOf(offerSequence))
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

    /**
     * A sell offer (the owner asks [amount] for the NFT) or a buy offer (anyone bids [amount]; [owner]
     * is then required). [destination] limits who may accept a sell offer.
     */
    fun nftCreateOffer(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        nftId: String,
        amount: CurrencyAmount,
        sell: Boolean,
        owner: String? = null,
        destination: String? = null,
    ): NfTokenCreateOffer = NfTokenCreateOffer.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .nfTokenId(NfTokenId.of(nftId))
        .amount(amount)
        .apply { if (!sell) owner(Address.of(requireNotNull(owner) { "A buy offer needs the NFT's owner." })) }
        .apply { if (!destination.isNullOrBlank()) destination(Address.of(destination.trim())) }
        .flags(NfTokenCreateOfferFlags.builder().tfSellToken(sell).build())
        .build()

    /** Accepts one side directly: pass [sellOffer] to buy, or [buyOffer] to sell to a bidder. */
    fun nftAcceptOffer(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        sellOffer: String? = null,
        buyOffer: String? = null,
    ): NfTokenAcceptOffer = NfTokenAcceptOffer.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .apply { if (sellOffer != null) sellOffer(Hash256.of(sellOffer)) }
        .apply { if (buyOffer != null) buyOffer(Hash256.of(buyOffer)) }
        .build()

    fun nftCancelOffer(from: Address, key: PublicKey, fill: Autofill, offerIds: List<String>): NfTokenCancelOffer =
        NfTokenCancelOffer.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .tokenOffers(offerIds.map { Hash256.of(it) })
            .build()

    /** Opens a payment channel. [settleDelaySeconds] is how long the payee has to claim after the payer asks to close. */
    fun channelCreate(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        destination: String,
        drops: Long,
        settleDelaySeconds: Long,
    ): PaymentChannelCreate = PaymentChannelCreate.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .destination(Address.of(destination))
        .amount(XrpCurrencyAmount.ofDrops(drops))
        .settleDelay(UnsignedInteger.valueOf(settleDelaySeconds))
        .publicKey(key.base16Value())
        .build()

    fun channelFund(from: Address, key: PublicKey, fill: Autofill, channelId: String, drops: Long): PaymentChannelFund =
        PaymentChannelFund.builder()
            .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
            .channel(Hash256.of(channelId))
            .amount(XrpCurrencyAmount.ofDrops(drops))
            .build()

    /**
     * The payee redeems a signed claim ([claimDrops], [signature] and the payer's [payerPublicKey]), or
     * either side closes the channel with [close]. With no claim it just requests the close.
     */
    fun channelClaim(
        from: Address,
        key: PublicKey,
        fill: Autofill,
        channelId: String,
        claimDrops: Long? = null,
        signature: String? = null,
        payerPublicKey: String? = null,
        close: Boolean = false,
    ): PaymentChannelClaim = PaymentChannelClaim.builder()
        .account(from).signingPublicKey(key).fee(fee(fill)).sequence(seq(fill)).lastLedgerSequence(last(fill))
        .channel(Hash256.of(channelId))
        .apply {
            if (claimDrops != null && signature != null && payerPublicKey != null) {
                balance(XrpCurrencyAmount.ofDrops(claimDrops))
                amount(XrpCurrencyAmount.ofDrops(claimDrops))
                signature(signature)
                publicKey(payerPublicKey)
            }
        }
        .flags(PaymentChannelClaimFlags.builder().tfClose(close).build())
        .build()

    internal fun unhex(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    internal fun hex(text: String): String = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }

    private fun fee(fill: Autofill) = XrpCurrencyAmount.ofDrops(fill.feeDrops)
    private fun seq(fill: Autofill) = UnsignedInteger.valueOf(fill.sequence)
    private fun last(fill: Autofill) = UnsignedInteger.valueOf(fill.lastLedgerSequence)
}
