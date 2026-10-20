package com.ledgerlab.xrpl

/** One AMM pool's activity: how often liquidity went in or out, and how much its LP supply moved overall. */
data class PoolActivity(
    val pair: String,
    val deposits: Int,
    val withdrawals: Int,
    val swaps: Int,
    /** Compounded change in LP token supply, so +0.05 means the pool holds 5% more liquidity than at the start. */
    val liquidityChange: Double,
)

/** Counts behind the DEX, AMM, token, NFT and account panels. */
data class ActivitySnapshot(
    val offersCreated: Long = 0,
    val offersFilled: Long = 0,
    val offersCancelled: Long = 0,
    /** Trading pairs by number of offers and fills, busiest first. */
    val topPairs: List<Pair<String, Long>> = emptyList(),
    /** Pools by number of transactions, busiest first. */
    val pools: List<PoolActivity> = emptyList(),
    val poolsCreated: Long = 0,
    /** Issued tokens ("CUR.rIssuer") by number of transactions that touched them, busiest first. */
    val topTokens: List<Pair<String, Long>> = emptyList(),
    val nftMints: Long = 0,
    val nftBurns: Long = 0,
    val nftOffers: Long = 0,
    val nftSales: Long = 0,
    val nftSaleVolumeDrops: Long = 0,
    val biggestNftSaleDrops: Long = 0,
    val accountsCreated: Long = 0,
    val accountsDeleted: Long = 0,
    val escrowsCreated: Long = 0,
    val escrowsFinished: Long = 0,
    val escrowsCancelled: Long = 0,
    /** XRP put into new escrows. */
    val escrowLockedDrops: Long = 0,
    /** XRP that finished escrows paid out. */
    val escrowReleasedDrops: Long = 0,
    val checksCreated: Long = 0,
    val checksCashed: Long = 0,
    val checksCancelled: Long = 0,
    val channelsOpened: Long = 0,
    val channelClaims: Long = 0,
    /** Accounts sending the most transactions, busiest first. */
    val topAccounts: List<Pair<String, Long>> = emptyList(),
    /** Successful transactions counted, the denominator for shares. */
    val transactions: Long = 0,
    val payments: Long = 0,
    val paymentsWithTag: Long = 0,
    val withMemos: Long = 0,
    /** Decoded MemoType values, most used first. */
    val topMemoTypes: List<Pair<String, Long>> = emptyList(),
) {
    val fillRate: Double get() = if (offersCreated == 0L) 0.0 else offersFilled.toDouble() / offersCreated
}

/**
 * Tallies successful transactions into an [ActivitySnapshot]. Fed by the live stream for "this session",
 * and by the sample ledgers for a past period, where [snapshot]'s scale turns sample counts into estimates.
 * Not thread safe.
 */
class ActivityTally(private val top: Int = 5) {
    private var offersCreated = 0L
    private var offersFilled = 0L
    private var offersCancelled = 0L
    private val pairs = HashMap<String, Long>()
    private val pools = HashMap<String, Pool>()
    private var poolsCreated = 0L
    private val tokens = HashMap<String, Long>()
    private var nftMints = 0L
    private var nftBurns = 0L
    private var nftOffers = 0L
    private var nftSales = 0L
    private var nftVolume = 0L
    private var nftBiggest = 0L
    private var accountsCreated = 0L
    private var accountsDeleted = 0L
    private val counts = HashMap<String, Long>()
    private var escrowLocked = 0L
    private var escrowReleased = 0L
    private val senders = HashMap<String, Long>()
    private var transactions = 0L
    private var payments = 0L
    private var paymentsWithTag = 0L
    private var withMemos = 0L
    private val memoTypes = HashMap<String, Long>()

    private class Pool {
        var deposits = 0
        var withdrawals = 0
        var swaps = 0
        var growth = 1.0
    }

    fun add(tx: StreamTx) {
        if (!tx.succeeded) return
        val e = tx.effects
        transactions++
        counts.merge(tx.type, 1L, Long::plus)
        if (tx.account.isNotEmpty()) senders.merge(tx.account, 1L, Long::plus)
        // A long session on Mainnet sees a lot of one-off senders; drop them so the map stays small.
        if (senders.size > MAX_SENDERS) senders.values.removeAll { it <= 1L }
        if (tx.type == "Payment") {
            payments++
            if (e.destinationTag) paymentsWithTag++
        }
        if (e.memoTypes.isNotEmpty()) {
            withMemos++
            for (t in e.memoTypes) memoTypes.merge(t, 1L, Long::plus)
        }
        if (tx.type == "EscrowCreate") escrowLocked += tx.xrpDrops
        escrowReleased += e.escrowReleasedDrops ?: 0L
        offersFilled += e.offersFilled
        accountsCreated += e.accountsCreated
        accountsDeleted += e.accountsDeleted
        for (token in e.tokens) tokens.merge(token, 1L, Long::plus)
        when (tx.type) {
            "OfferCreate" -> offersCreated++
            "OfferCancel" -> offersCancelled++
            "NFTokenMint" -> nftMints++
            "NFTokenBurn" -> nftBurns++
            "NFTokenCreateOffer" -> nftOffers++
            "NFTokenAcceptOffer" -> {
                nftSales++
                val drops = e.nftSaleDrops ?: 0L
                nftVolume += drops
                nftBiggest = maxOf(nftBiggest, drops)
            }
            "AMMCreate" -> poolsCreated++
        }
        if (e.pair != null && (tx.type == "OfferCreate" || e.offersFilled > 0)) pairs.merge(e.pair, 1L, Long::plus)
        if (e.pair != null && tx.type.startsWith("AMM")) {
            val pool = pools.getOrPut(e.pair) { Pool() }
            when (tx.type) {
                "AMMDeposit", "AMMCreate" -> pool.deposits++
                "AMMWithdraw" -> pool.withdrawals++
            }
            e.lpChange?.let { pool.growth *= (1 + it).coerceAtLeast(0.0) }
        } else if (e.pair != null && e.ammSwap) {
            pools.getOrPut(e.pair) { Pool() }.swaps++
        }
    }

    /** [scale] multiplies every count, e.g. ledgers in the period divided by ledgers sampled. */
    fun snapshot(scale: Double = 1.0): ActivitySnapshot {
        fun s(n: Long) = Math.round(n * scale)
        fun s(n: Int) = Math.round(n * scale).toInt()
        return ActivitySnapshot(
            offersCreated = s(offersCreated),
            offersFilled = s(offersFilled),
            offersCancelled = s(offersCancelled),
            topPairs = pairs.entries.sortedByDescending { it.value }.take(top).map { it.key to s(it.value) },
            pools = pools.entries
                .sortedByDescending { it.value.deposits + it.value.withdrawals + it.value.swaps }
                .take(top)
                .map { (pair, p) -> PoolActivity(pair, s(p.deposits), s(p.withdrawals), s(p.swaps), p.growth - 1) },
            poolsCreated = s(poolsCreated),
            topTokens = tokens.entries.sortedByDescending { it.value }.take(top).map { it.key to s(it.value) },
            nftMints = s(nftMints),
            nftBurns = s(nftBurns),
            nftOffers = s(nftOffers),
            nftSales = s(nftSales),
            nftSaleVolumeDrops = s(nftVolume),
            biggestNftSaleDrops = nftBiggest,
            accountsCreated = s(accountsCreated),
            accountsDeleted = s(accountsDeleted),
            escrowsCreated = s(count("EscrowCreate")),
            escrowsFinished = s(count("EscrowFinish")),
            escrowsCancelled = s(count("EscrowCancel")),
            escrowLockedDrops = s(escrowLocked),
            escrowReleasedDrops = s(escrowReleased),
            checksCreated = s(count("CheckCreate")),
            checksCashed = s(count("CheckCash")),
            checksCancelled = s(count("CheckCancel")),
            channelsOpened = s(count("PaymentChannelCreate")),
            channelClaims = s(count("PaymentChannelClaim")),
            topAccounts = senders.entries.sortedByDescending { it.value }.take(top).map { it.key to s(it.value) },
            transactions = s(transactions),
            payments = s(payments),
            paymentsWithTag = s(paymentsWithTag),
            withMemos = s(withMemos),
            topMemoTypes = memoTypes.entries.sortedByDescending { it.value }.take(top).map { it.key to s(it.value) },
        )
    }

    private fun count(type: String): Long = counts[type] ?: 0L

    private companion object {
        const val MAX_SENDERS = 20_000
    }

    fun reset() {
        offersCreated = 0
        offersFilled = 0
        offersCancelled = 0
        pairs.clear()
        pools.clear()
        poolsCreated = 0
        tokens.clear()
        nftMints = 0
        nftBurns = 0
        nftOffers = 0
        nftSales = 0
        nftVolume = 0
        nftBiggest = 0
        accountsCreated = 0
        accountsDeleted = 0
        counts.clear()
        escrowLocked = 0
        escrowReleased = 0
        senders.clear()
        transactions = 0
        payments = 0
        paymentsWithTag = 0
        withMemos = 0
        memoTypes.clear()
    }
}
