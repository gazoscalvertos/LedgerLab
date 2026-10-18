package com.ledgerlab.xrpl.labs

import com.ledgerlab.xrpl.wallet.Autofill
import com.ledgerlab.xrpl.wallet.WalletKeys
import com.ledgerlab.xrpl.wallet.XrplRpc
import org.xrpl.xrpl4j.crypto.keys.PublicKey
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.Transaction

data class TxOutcome(val hash: String?, val result: String, val validated: Boolean) {
    val success: Boolean get() = validated && result == "tesSUCCESS"
}

/**
 * The whole life of a transaction: autofill, sign on the device, submit, then wait for a validated
 * ledger. Blocking; [onStep] reports progress for the UI.
 */
object TxPipeline {
    fun run(
        rpc: XrplRpc,
        secret: String,
        address: String,
        onStep: (String) -> Unit = {},
        build: (Address, PublicKey, Autofill) -> Transaction,
    ): TxOutcome {
        onStep("Filling in sequence and fee")
        val fill = rpc.autofill(address)
        onStep("Signing on this phone")
        val signed = WalletKeys.sign(secret) { from, key -> build(from, key, fill) }
        onStep("Submitting")
        val submitted = rpc.submit(signed.blob)
        if (!submitted.accepted) {
            return TxOutcome(signed.hash, "${submitted.engineResult}: ${submitted.message}", validated = false)
        }
        onStep("Waiting for validation (${submitted.engineResult})")
        val final = rpc.waitForValidation(signed.hash, fill.lastLedgerSequence)
        return TxOutcome(signed.hash, final.result, final.validated)
    }
}
