# LedgerLab

An Android playground for the XRP Ledger. LedgerLab opens on a live dashboard that streams the
ledger in real time, and grows into a set of hands-on demos of what the XRPL can do.

## What's in it today

- **Menu**: the ☰ button opens a side menu that jumps to the stats (by group), The basics, Use cases, or straight
  into any lab. Stats, The basics and Use cases are each a heading that folds away.
- **Live dashboard** streaming the `ledger`, `transactions` and `validations` WebSocket feeds from a public rippled server:
  - a ledger "pulse" ring that fills while validators reach consensus and bursts on every close
  - throughput (tx/s), peak tx/s, average close time, base fee and reserves
  - the **TX lane**: every validated transaction streaks across a Matrix-style highway the moment its
    ledger closes, trailing the characters of its own hash. Colours show the transaction family, red means it
    failed, and gold "whales" are big XRP payments
  - **Whale alerts**: payments over a threshold you pick (1K to 1M XRP, 50K by default) pop up as cards over the
    lane and collect in a list, next to the biggest XRP payments seen this session
  - **Fee pressure gauge**: polls `fee` for how full the open ledger is, the queue and the escalated fee, and
    swings from green to amber to red as the network gets busy
  - **Ledger heartbeat**: an ECG trace where every close is a beat, spaced by its real interval, with ledgers/min
    and jitter
  - **DEX & AMM pulse**: offers placed vs filled, the busiest trading pairs, and AMM pools gaining or losing
    liquidity (read from each transaction's metadata)
  - **Tokens & NFTs**: the most active issued tokens, and NFT mints, sales, offers, burns and sale volume
  - **Account growth**: accounts created and deleted, and total XRP burned since genesis from `total_coins`
  - **Validators**: the network's published UNL as a live constellation, each validator coloured by how often it
    signed the same ledger as the network, with domains from `manifest` and amendment votes from `feature`.
    Validators don't publish where they are, so it's a ring rather than a world map. Each validator's software
    version (decoded from `server_version` on flag ledgers) and a version breakdown
  - **Amendments**: every amendment the server knows, as enabled, majority (with days until it activates),
    being voted on, or no votes yet
  - **Server & peers**: the connected server's version, state, peer count, uptime, load and history from
    `server_info` (there's no public count of every node on the network)
  - Stats are grouped into Overview, Whales, Markets and Network tabs
  - a transactions-per-ledger sparkline and a donut of what the network is being used for
  - **Time periods**: switch the stats between Live, 1h, 24h and 7d. Nodes keep ledgers but no running totals, so
    past periods read the ledgers at each end of the window (exact close time, and XRP burned from `total_coins`)
    plus 24 ledgers spread between them (estimated transaction counts, mix, volume, success rate, and the DEX,
    token, NFT and account panels)
- **Network switcher** for Testnet (default), Devnet and Mainnet. The choice is remembered and shared by every screen.
  Mainnet is read-only: the dashboard and explorer work, but everything that uses a wallet is switched off.
- **Wallet lab**: create (Ed25519 or secp256k1) or import wallets by secret, activate and top up from the
  Testnet/Devnet faucet, see balance, reserve and spendable XRP, send XRP payments (with destination tags), and browse
  recent activity with links to the explorer. Keys are generated and transactions signed on the phone with
  [xrpl4j](https://github.com/XRPLF/xrpl4j); secrets are stored encrypted with an Android Keystore AES key and never
  leave the device.

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
- **Use cases**, small real-world apps built from those features (both home-screen sections fold away). Each has its own
  app-style header, a diagram of who does what, and a line naming the XRPL features it uses:
  - **.xrp names**: a wallet plays the registry. Register `alice.xrp` for 10 XRP (pay, mint, hand over:
    four transactions in one tap), issue and sell subdomains like `pay.alice.xrp`, trade names with a 5% registry royalty,
    and pay anyone by name. Names are NFTs, resolved through Clio's `nfts_by_issuer` index. Owners publish DNS-style
    records (A, AAAA, CNAME, TXT, including hosts like `www.alice.xrp`) with one signed memo each, and a public resolver
    does forward lookups plus reverse lookups by IP or XRPL address
  - **Notary**: hash any file (or text) on the phone and write its SHA-256 into a memo on a no-op AccountSet. Verify later by
    hashing again and finding the match in the signer's history, with the ledger's close time as the timestamp
  - **Event tickets**: an organiser mints numbered ticket NFTs and lists them at face value. Open resale tickets carry a
    royalty; no-resale tickets are non-transferable, so the ledger refuses scalping. At the door the organiser burns the
    ticket from the fan's wallet
  - **Pay-per-minute**: the payer opens a channel once, then the phone signs a cumulative claim every second at a chosen
    rate. The provider settles the whole session with one PaymentChannelClaim
  - **Freelance jobs**: the client locks the budget in an escrow with a PREIMAGE-SHA-256 crypto-condition and a deadline.
    Approving hands the freelancer the fulfillment to claim it; a guessed secret fails; after the deadline the client refunds
  - **Crowdfunding**: all-or-nothing. Each pledge is a conditional escrow to the creator with the deadline as CancelAfter.
    If the goal is met the creator collects every pledge; if not, one tap cancels them all back to the backers
  - **Loyalty points**: a shop issues its own token with optional RequireAuth (approve members) and clawback, guided step by
    step. Members get a loyalty card, spend points back to the shop, and buy or sell points against each other's DEX offers
  - **Subscriptions**: the customer writes a Check per billing period with a plan InvoiceID; the merchant cashes it, and
    the customer can cancel any time before
  - **Shared treasury**: SignerListSet makes a club wallet need 2 of 3 signers. A payment is drafted once, each signer
    signs it on the phone, and the combined multi-signed transaction is submitted
  - **Verified badges**: XLS-70 credentials. An issuer vouches (KYC, Over18…), the subject accepts, anyone verifies from
    the ledger, and the issuer can revoke

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
10. In **Crowdfunding**, launch a short campaign, pledge from two wallets, and either collect or let it expire and refund.
11. In **Loyalty points**, set up a fresh shop wallet, join as a customer, approve, award, spend and claw back.
12. In **Subscriptions**, pay for a plan as the customer, then collect it as the merchant.
13. In **Shared treasury**, set 2 of 3 signers, draft a payment, approve it as two signers and send it.
14. In **Verified badges**, issue a KYC badge, accept it as the subject, then verify it from the Verify tab.
15. Paste any hash from the activity lists into **Explorer**.

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
