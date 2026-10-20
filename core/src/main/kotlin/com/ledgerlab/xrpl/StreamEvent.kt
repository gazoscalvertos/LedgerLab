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

    /** One validator's vote that a ledger is the right one, from the `validations` stream. */
    data class ValidationReceived(val validation: Validation) : StreamEvent
}

data class Validation(
    /** The key that signed this validation (base58 `n9…`). Usually an ephemeral key a manifest ties to [masterKey]. */
    val signingKey: String,
    /** The validator's long-lived identity, when the server knows its manifest. */
    val masterKey: String?,
    val ledgerIndex: Long,
    val ledgerHash: String,
    /** Amendment IDs this validator votes for. Only sent on flag ledgers, every 256 ledgers. */
    val amendments: List<String>?,
    /** The software it runs, e.g. "2.3.0", decoded from `server_version`. Only sent on flag ledgers too. */
    val version: String? = null,
)

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
    val hash: String = "",
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
    /** Ledger close time in seconds since the Ripple epoch, when known (not sent on the live stream). */
    val closeTime: Long? = null,
    /** What it did to the ledger, read from its metadata. */
    val effects: TxEffects = TxEffects.NONE,
) {
    val succeeded: Boolean get() = result == "tesSUCCESS"
    val xrpDrops: Long get() = (amount as? Amount.Xrp)?.drops ?: 0L
}

sealed interface Amount {
    data class Xrp(val drops: Long) : Amount

    data class Issued(val currency: String, val value: String, val issuer: String?) : Amount
}
