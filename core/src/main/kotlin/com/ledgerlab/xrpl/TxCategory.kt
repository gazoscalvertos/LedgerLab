package com.ledgerlab.xrpl

/** Groups the ~50 XRPL transaction types into a few families that are easy to chart. */
enum class TxCategory(val label: String) {
    PAYMENT("Payments"),
    DEX("DEX"),
    AMM("AMM"),
    NFT("NFTs"),
    TRUST("Trust lines"),
    ESCROW("Escrow & channels"),
    ACCOUNT("Account"),
    OTHER("Other");

    companion object {
        fun of(transactionType: String): TxCategory = when {
            transactionType == "Payment" -> PAYMENT
            transactionType.startsWith("Offer") -> DEX
            transactionType.startsWith("AMM") -> AMM
            transactionType.startsWith("NFToken") -> NFT
            transactionType == "TrustSet" -> TRUST
            transactionType.startsWith("Escrow") ||
                transactionType.startsWith("PaymentChannel") ||
                transactionType.startsWith("Check") -> ESCROW
            transactionType in ACCOUNT_TYPES -> ACCOUNT
            else -> OTHER
        }

        private val ACCOUNT_TYPES = setOf(
            "AccountSet",
            "AccountDelete",
            "SetRegularKey",
            "SignerListSet",
            "DepositPreauth",
            "TicketCreate",
        )
    }
}
