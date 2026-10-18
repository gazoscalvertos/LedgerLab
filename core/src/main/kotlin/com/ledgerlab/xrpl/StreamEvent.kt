package com.ledgerlab.xrpl

/** Everything the dashboard can learn from a `subscribe` to the ledger and transactions streams. */
sealed interface StreamEvent {
    /** The socket is open and the subscribe request was sent. */
    data object Connected : StreamEvent

    /** The socket dropped; a reconnect is on its way. */
    data class Disconnected(val reason: String?) : StreamEvent

    /** The reply to our subscribe request, describing the most recent validated ledger. */
    data class Subscribed(
        val ledgerIndex: Long,
        val feeBaseDrops: Long?,
        val reserveBaseDrops: Long?,
        val reserveIncDrops: Long?,
    ) : StreamEvent

    data class LedgerClosed(val ledger: LedgerClose) : StreamEvent

    data class Transaction(val tx: StreamTx) : StreamEvent
}

data class LedgerClose(
    val index: Long,
    /** Close time in seconds since the Ripple epoch (2000-01-01T00:00:00Z). */
    val closeTime: Long,
    val txnCount: Int,
    val feeBaseDrops: Long,
    val reserveBaseDrops: Long,
    val reserveIncDrops: Long,
    /** Wall-clock millis when the app received this ledger, used for close-interval timing. */
    val receivedAtMs: Long,
)

data class StreamTx(
    val hash: String,
    val type: String,
    val category: TxCategory,
    val account: String,
    val destination: String?,
    val amount: Amount?,
    val feeDrops: Long,
    val result: String,
    val ledgerIndex: Long,
) {
    val succeeded: Boolean get() = result == "tesSUCCESS"
    val xrpDrops: Long get() = (amount as? Amount.Xrp)?.drops ?: 0L
}

sealed interface Amount {
    data class Xrp(val drops: Long) : Amount

    data class Issued(val currency: String, val value: String, val issuer: String?) : Amount
}
