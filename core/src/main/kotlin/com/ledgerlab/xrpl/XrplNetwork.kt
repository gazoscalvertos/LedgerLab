package com.ledgerlab.xrpl

/** The public XRP Ledger networks LedgerLab can talk to. Testnet is the default playground. */
enum class XrplNetwork(
    val label: String,
    val wsUrl: String,
    val faucetUrl: String?,
    val explorerUrl: String,
) {
    TESTNET(
        label = "Testnet",
        wsUrl = "wss://s.altnet.rippletest.net:51233",
        faucetUrl = "https://faucet.altnet.rippletest.net/accounts",
        explorerUrl = "https://testnet.xrpl.org",
    ),
    DEVNET(
        label = "Devnet",
        wsUrl = "wss://s.devnet.rippletest.net:51233",
        faucetUrl = "https://faucet.devnet.rippletest.net/accounts",
        explorerUrl = "https://devnet.xrpl.org",
    ),
    MAINNET(
        label = "Mainnet",
        wsUrl = "wss://s1.ripple.com",
        faucetUrl = null,
        explorerUrl = "https://livenet.xrpl.org",
    );

    val isRealMoney: Boolean get() = this == MAINNET

    companion object {
        val DEFAULT = TESTNET

        fun fromName(name: String?): XrplNetwork = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
