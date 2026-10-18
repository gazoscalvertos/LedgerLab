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
- **Network switcher** for Testnet (default), Devnet and Mainnet. The choice is remembered.

## Coming next

1. Wallet: create or import a wallet and fund it from the Testnet faucet
2. Feature demos: tokens and trust lines, DEX offers, NFTs, escrow and AMM, each with a "how it works" panel
3. Explorer view for any account or transaction, plus screenshots

## Getting the APK

Every pull request and push builds a debug APK in GitHub Actions (the **Android build** workflow, artifact
`ledgerlab-debug-apk`). Each merge to `main` also refreshes the **latest** pre-release on the Releases page, so you
can download `app-debug.apk` straight onto a phone running Android 8.0 or newer and allow installs from your browser.

## Project layout

| Module | What it holds |
| --- | --- |
| `core` | Plain Kotlin/JVM: network definitions, the WebSocket stream parser and the rolling dashboard stats, with unit tests |
| `app` | The Jetpack Compose Android app: the stream client, view model, theme and dashboard components |

Build locally with `./gradlew :core:test :app:assembleDebug` (JDK 17 and the Android SDK required).
