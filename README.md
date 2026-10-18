# LedgerLab

An Android playground for the XRP Ledger. LedgerLab opens on a live dashboard that streams the
ledger in real time, and grows into a set of hands-on demos of what the XRPL can do.

## What's in it today

- **Live dashboard** streaming the `ledger` and `transactions` WebSocket feeds from a public rippled server:
  - a ledger "pulse" ring that fills while validators reach consensus and bursts on every close
  - throughput (tx/s), peak tx/s, average close time, base fee and reserves
  - the **TX lane**: every validated transaction streaks across a Matrix-style highway the moment its
    ledger closes, trailing the characters of its own hash. Colours show the transaction family, red means it
    failed, and gold "whales" are XRP payments of 50K XRP or more
  - a transactions-per-ledger sparkline, a donut of what the network is being used for, and the biggest
    XRP payments seen this session
- **Network switcher** for Testnet (default), Devnet and Mainnet. The choice is remembered and shared by every screen.
- **Wallet lab**: create (Ed25519 or secp256k1) or import wallets by secret, activate and top up from the
  Testnet/Devnet faucet, see balance, reserve and spendable XRP, send XRP payments (with destination tags), and browse
  recent activity with links to the explorer. Keys are generated and transactions signed on the phone with
  [xrpl4j](https://github.com/XRPLF/xrpl4j); secrets are stored encrypted with an Android Keystore AES key and never
  leave the device. Mainnet payments ask for confirmation first.

- **Feature labs**, each with a "How it works" panel showing the transaction JSON:
  - **Tokens**: issue your own currency (enable rippling, open a trust line, issue) and send it on between holders
  - **DEX**: live order book for any XRP/token pair, place buy and sell offers, cancel open offers. Tokens your wallets issue or hold show up as one-tap picks
  - **NFTs**: pick an image, video or audio file, pin it to IPFS through Pinata, choose the metadata standard
    (XLS-24, OpenSea style, or a bare media link), and mint with a royalty, transferable and XRP-only flags.
    Then list NFTs for XRP or any token, bid, accept, buy and cancel offers, including private sales to one buyer
  - **Escrow**: lock XRP or tokens (XLS-85 token escrow) until a time, watch the countdown, then release it or return it after expiry
  - **Channels**: open an XRP payment channel, stream off-ledger claims signed on the phone, check them with
    `channel_verify`, and redeem or close as the payee
  - **AMM**: inspect an XRP/token pool (reserves, price, LP tokens, fee), create one or add liquidity
  - **Explorer**: look up any account, transaction or ledger, with raw JSON and links between them
- **Mockup apps**, small real-world ideas built from those features:
  - **.xrp names**: a wallet plays the registry. Register `alice.xrp` for 10 XRP (pay, mint, hand over:
    four transactions in one tap), issue and sell subdomains like `pay.alice.xrp`, trade names with a 5% registry royalty,
    and pay anyone by name. Names are NFTs, resolved through Clio's `nfts_by_issuer` index
  - **Notary**: hash any file (or text) on the phone and write its SHA-256 into a memo on a no-op AccountSet. Verify later by
    hashing again and finding the match in the signer's history, with the ledger's close time as the timestamp
  - **Event tickets**: an organiser mints numbered ticket NFTs and lists them at face value. Open resale tickets carry a
    royalty; no-resale tickets are non-transferable, so the ledger refuses scalping. At the door the organiser burns the
    ticket from the fan's wallet
  - **Pay-per-minute**: the payer opens a channel once, then the phone signs a cumulative claim every second at a chosen
    rate. The provider settles the whole session with one PaymentChannelClaim
  - **Freelance jobs**: the client locks the budget in an escrow with a PREIMAGE-SHA-256 crypto-condition and a deadline.
    Approving hands the freelancer the fulfillment to claim it; a guessed secret fails; after the deadline the client refunds

## Try it on Testnet

1. Open **Wallet**, create two wallets and activate both from the faucet.
2. In **Tokens**, pick one as holder and the other as issuer, then run the three steps.
3. Send the token to a third wallet, trade it in **DEX**, seed an **AMM** pool, lock it in **Escrow**, or price an **NFT** in it.
4. In **Channels**, open a channel between two wallets, tap **Pay** a few times as the payer, then switch to the payee and redeem.
5. In **.xrp names**, make one wallet the registry, register a name from another, issue a subdomain and sell it to a third, then pay it by name.
6. In **Notary**, notarise a photo, then verify it (and a slightly edited copy) from the Verify tab.
7. In **Event tickets**, mint three tickets as the organiser, buy one as a fan, resell it to a third wallet, then check it in at the Door.
8. In **Pay-per-minute**, open a channel, run the meter for a minute, verify the claim and settle.
9. In **Freelance jobs**, post a job as the client, try claiming as the freelancer, approve, then claim for real.
10. Paste any hash from the activity lists into **Explorer**.

## Getting the APK

Every pull request and push builds a debug APK in GitHub Actions (the **Android build** workflow, artifact
`ledgerlab-debug-apk`). Each merge to `main` also refreshes the **latest** pre-release on the Releases page, so you
can download `app-debug.apk` straight onto a phone running Android 8.0 or newer and allow installs from your browser.
Builds are signed with the fixed debug key in `app/debug.keystore`, so a new build installs over the old one and keeps
your wallets. It's a public test key: never use it for a store release.

Minting from a file needs a free [Pinata](https://app.pinata.cloud) API key (the JWT), pasted once in the NFT lab and
stored encrypted on the phone. Token escrow needs the TokenEscrow amendment; if a network replies `temDISABLED`, try Devnet.

## Project layout

| Module | What it holds |
| --- | --- |
| `core` | Plain Kotlin/JVM: network definitions, the WebSocket stream parser, rolling dashboard stats, the wallet layer (keys, signing, JSON-RPC client, faucet) and the labs layer (transaction builders, queries, submit-and-validate pipeline), with unit tests |
| `app` | The Jetpack Compose Android app: the stream client, view model, theme and dashboard components |

Build locally with `./gradlew :core:test :app:assembleDebug` (JDK 17 and the Android SDK required).
