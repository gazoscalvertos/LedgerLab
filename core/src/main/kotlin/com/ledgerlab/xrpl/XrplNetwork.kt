package com.ledgerlab.xrpl

/** The public XRP Ledger networks LedgerLab can talk to. Testnet is the default playground. */
enum class XrplNetwork(
    val label: String,
    val wsUrl: String,
    val rpcUrl: String,
    /** A Clio server, which adds NFT indexes (nft_info, nfts_by_issuer) that rippled doesn't have. */
    val clioRpcUrl: String,
    val faucetUrl: String?,
    val explorerUrl: String,
) {
    TESTNET(
        label = "Testnet",
        wsUrl = "wss://s.altnet.rippletest.net:51233",
        rpcUrl = "https://s.altnet.rippletest.net:51234",
        clioRpcUrl = "https://clio.altnet.rippletest.net:51234",
        faucetUrl = "https://faucet.altnet.rippletest.net/accounts",
        explorerUrl = "https://testnet.xrpl.org",
    ),
    DEVNET(
        label = "Devnet",
        wsUrl = "wss://s.devnet.rippletest.net:51233",
        rpcUrl = "https://s.devnet.rippletest.net:51234",
        clioRpcUrl = "https://clio.devnet.rippletest.net:51234",
        faucetUrl = "https://faucet.devnet.rippletest.net/accounts",
        explorerUrl = "https://devnet.xrpl.org",
    ),
    MAINNET(
        label = "Mainnet",
        wsUrl = "wss://s1.ripple.com",
        rpcUrl = "https://s1.ripple.com:51234",
        clioRpcUrl = "https://s2.ripple.com:51234",
        faucetUrl = null,
        explorerUrl = "https://livenet.xrpl.org",
    );

    val isRealMoney: Boolean get() = this == MAINNET

    companion object {
        val DEFAULT = TESTNET

        fun fromName(name: String?): XrplNetwork = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
