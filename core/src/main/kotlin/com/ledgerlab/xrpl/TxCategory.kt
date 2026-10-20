package com.ledgerlab.xrpl

/**
 * Groups the XRPL transaction types into families that are easy to chart. Every type the ledger defines today
 * has a home; [OTHER] only catches types newer than this list.
 */
enum class TxCategory(val label: String) {
    PAYMENT("Payments"),
    DEX("DEX"),
    AMM("AMM"),
    NFT("NFTs"),
    TRUST("Trust lines & tokens"),
    MPT("Multi-purpose tokens"),
    ESCROW("Escrow & channels"),
    CHECK("Checks"),
    ORACLE("Price oracles"),
    IDENTITY("Identity & credentials"),
    VAULT("Vaults & lending"),
    BRIDGE("Cross-chain bridges"),
    ACCOUNT("Account settings"),
    NETWORK("Network (pseudo)"),
    OTHER("New types");

    companion object {
        fun of(transactionType: String): TxCategory = when {
            transactionType == "Payment" -> PAYMENT
            transactionType.startsWith("Offer") -> DEX
            transactionType.startsWith("AMM") -> AMM
            transactionType.startsWith("NFToken") -> NFT
            transactionType == "TrustSet" || transactionType == "Clawback" -> TRUST
            transactionType.startsWith("MPToken") -> MPT
            transactionType.startsWith("Escrow") || transactionType.startsWith("PaymentChannel") -> ESCROW
            transactionType.startsWith("Check") -> CHECK
            transactionType.startsWith("Oracle") -> ORACLE
            transactionType.startsWith("DID") ||
                transactionType.startsWith("Credential") ||
                transactionType.startsWith("PermissionedDomain") -> IDENTITY
            transactionType.startsWith("Vault") || transactionType.startsWith("Loan") -> VAULT
            transactionType.startsWith("XChain") -> BRIDGE
            transactionType in ACCOUNT_TYPES -> ACCOUNT
            transactionType in NETWORK_TYPES -> NETWORK
            else -> OTHER
        }

        private val ACCOUNT_TYPES = setOf(
            "AccountSet",
            "AccountDelete",
            "SetRegularKey",
            "SignerListSet",
            "DepositPreauth",
            "TicketCreate",
            "DelegateSet",
            "Batch",
            "LedgerStateFix",
        )

        /** Written by the network itself, not by users: amendment votes, fee votes and UNL changes. */
        private val NETWORK_TYPES = setOf("EnableAmendment", "SetFee", "UNLModify")
    }
}
