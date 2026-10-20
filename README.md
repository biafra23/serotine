# Serotine

A Compose Multiplatform wallet for Ethereum accounts (EOAs) and
[Safe{Wallet}](https://safe.global) smart accounts, built on an embedded
[Myotis](https://github.com/biafra23/myotis) engine: every chain read is
verified against Ethereum's sync committee by a light client running inside
the app, with no RPC provider in the loop. Android and desktop (Linux, macOS,
Windows) today; iOS next. For now the app works on the Sepolia testnet only.

## Status

Early, with no release tagged yet (the version is `0.1.0-SNAPSHOT`).
What the app does today:

- **Accounts.** Create an account from a new recovery phrase, import one from
  a phrase, or watch any address, which is told apart from verified code and
  storage as an EOA (delegated or not), a Safe or another contract. Accounts
  are kept across restarts and can be renamed and removed.
- **Keys.** An imported or created seed is held in an envelope that opens only
  with the Argon2id-stretched wallet password AND, where the device has one,
  a secret of its secure hardware (the Android Keystore, a Mac's Secure
  Enclave); Windows and Linux stand on the password alone for now, and the
  app says so. Biometric unlock is an opt-in, bound to a hardware key
  (docs/keys-and-signing.md).
- **Verified balances.** ETH and token balances, token metadata and prices are
  read through the embedded light client and labelled with the block they were
  proven at; while no fresh verified head exists, the last proven balance stays
  on screen, dated and marked as possibly changed. A built-in token list with
  bundled icons, tokens added by address, and a display currency priced by
  Chainlink feeds read on chain ("testnet prices").
- **Send and receive.** ETH and ERC-20 transfers with a verified nonce, fee and
  gas estimate, a review step, and a status screen that follows the verified
  receipt to finalization; a recipient may be an ENS name, resolved with a
  verified read. A QR code to receive.
- **Safe{Wallet}.** Watch an existing Safe (recognised only when its proxy
  and singleton match the pinned 1.3.0, 1.4.1 or 1.5.0 deployments) or
  create a Safe 1.4.1 (its address predicted locally, the deployment paid by
  an account held here). Propose, sign, co-sign and execute Safe
  transactions: a signing request travels between devices as a QR code or
  text, never through the Safe Transaction Service, and each co-signer
  decodes and checks it against the chain; an owner may approve on chain
  instead. A transaction that would set any fallback handler but the pinned
  one, remove it, or enable a module is shown and never signed.
- **Swaps on Uniswap v3** from an account or a Safe: verified quotes from the
  pinned contracts, an exact-amount approval, a slippage tolerance (0.5 % by
  default) and the price impact, with the amount received read from the
  verified receipt (docs/swaps.md).
- **Activity.** Every transaction this device sent, kept for good with how it
  ended as the device saw it.
- **Network upgrades.** When peers announce a network upgrade this version
  does not support, the app warns ahead of it, notifies (on Android also while
  it is closed), and once the upgrade has cut it off shows nothing but the way
  to the update.

Not built yet: swaps through CoW Protocol (waiting on the owner's ruling on
its order API), transactions received or sent from elsewhere in Activity,
a Safe owned by a device's hardware P-256 key, recovery modules, dApp
connections, the iOS app, and any network other than Sepolia.
docs/wallet-ux.md describes every screen, with what is built and what is not.

## Build & run

```bash
./gradlew build                          # every module: compile, unit + UI tests, Android lint
./gradlew :desktop-app:run               # the desktop app, in-process
./gradlew :android-app:installDebug      # the debug app (eth.serotine.debug) on a connected device
./gradlew :desktop-app:packageDeb        # the Linux installer   (on Linux)
./gradlew :desktop-app:packageDmg        # the macOS installer   (on macOS)
./gradlew :desktop-app:packageMsi        # the Windows installer (on Windows, WiX 3 on the PATH)
./gradlew :ui:desktopTest --tests '*PreviewsTest' --rerun -Pscreenshots=build/screenshots
                                         # every screen's previews as PNGs
```

Needs JDK 21 and, for the Android modules, an Android SDK (`ANDROID_HOME`, or
`sdk.dir` in a git-ignored `local.properties`). The iOS targets of the shared
modules build on macOS only and are skipped elsewhere.

## Layout

| module | role |
|---|---|
| `domain` | entities, value objects, ports (repository interfaces) and use cases — pure Kotlin |
| `data` | the adapters behind the ports: the files the wallet keeps (accounts, tokens, the Safe queue, sent transactions, settings), the key store, the pinned Safe, Uniswap and Chainlink deployments, and the Myotis engine adapter |
| `ui` | the shared Compose Multiplatform UI: screens, ViewModels, UI state, `AppGraph` |
| `android-app` | the Android host (package `eth.serotine`; the debug build is `eth.serotine.debug`) |
| `desktop-app` | the desktop JVM host and the jpackage configuration for the three installers |

The dependency rule, the MVVM contract and how to add a feature:
[docs/architecture.md](docs/architecture.md). How keys are held, what the
user chooses between, and what a hardware key can and cannot guarantee:
[docs/keys-and-signing.md](docs/keys-and-signing.md). The rules every change
goes through (review, PR, CI, release): [CLAUDE.md](CLAUDE.md). The screens
and the rules they follow: [docs/wallet-ux.md](docs/wallet-ux.md); swaps:
[docs/swaps.md](docs/swaps.md); the light client inside the app:
[docs/embedding-myotis.md](docs/embedding-myotis.md).

## Releases

Every `v*` tag publishes a pre-release with the signed Android APK, the debug
APK, the macOS dmgs (arm64 + x64), the Linux debs (x64, and arm64 while the
repository is public) and the Windows msi. The
tag's version must match the `version` in `build.gradle.kts`
(release-version-guard.yml refuses otherwise), and the Android release key
lives only in the `android-release` environment
([docs/android-release-signing.md](docs/android-release-signing.md)).

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
