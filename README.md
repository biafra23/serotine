# Serotine

A Compose Multiplatform wallet for Ethereum accounts (EOAs) and
[Safe{Wallet}](https://safe.global) smart accounts, built on an embedded
[Myotis](https://github.com/biafra23/myotis) engine: every chain read is
verified against Ethereum's sync committee by a light client running inside
the app, with no RPC provider in the loop. Android, desktop (Linux, macOS,
Windows) and iOS (a development build for now). For now the app works on the
Sepolia testnet only.

**Status: skeleton.** The module graph, the MVVM/clean-architecture seams, the
tests and the release workflows are in place. The first screen lists the
accounts, kept across restarts, with their native balances verified by the
embedded Myotis light client (docs/embedding-myotis.md), and adds an account
from its seed phrase: its first account, the seed stored encrypted under the
wallet password and, where the device has one, a key in its secure hardware
(the Android Keystore, a Mac's Secure Enclave; docs/keys-and-signing.md).
When peers announce a network upgrade this version does not support, the app
warns ahead of it, notifies (on Android also while it is closed), and once
the upgrade has cut it off shows nothing but the way to the update
(docs/embedding-myotis.md, *Network upgrades*). Signing comes next.

## Build & run

```bash
./gradlew build                          # every module: compile, unit + UI tests, Android lint
./gradlew :desktop-app:run               # the desktop app, in-process
./gradlew :android-app:installDebug      # the debug app (eth.serotine.debug) on a connected device
./gradlew :desktop-app:packageDeb        # the Linux installer   (on Linux)
./gradlew :desktop-app:packageDmg        # the macOS installer   (on macOS)
./gradlew :desktop-app:packageMsi        # the Windows installer (on Windows, WiX 3 on the PATH)

# The iOS app (on macOS, with Xcode): generate its Xcode project, then build and run it from Xcode
brew install xcodegen
cd ios-app/xcode && SEROTINE_DEVELOPMENT_TEAM=<team id> xcodegen generate && open Serotine.xcodeproj
```

Needs JDK 21 and, for the Android modules, an Android SDK (`ANDROID_HOME`, or
`sdk.dir` in a git-ignored `local.properties`). The iOS targets of the shared
modules and the iOS app build on macOS only and are skipped elsewhere. The
team id (Xcode > Settings > Accounts) signs the iOS app for a device; the
simulator needs none.

## Layout

| module | role |
|---|---|
| `domain` | entities, value objects, ports (repository interfaces) and use cases — pure Kotlin |
| `data` | the adapters behind the ports: the account list in a file, the key store for imported seeds, and the Myotis engine adapter |
| `ui` | the shared Compose Multiplatform UI: screens, ViewModels, UI state, `AppGraph` |
| `android-app` | the Android host (package `eth.serotine`; the debug build is `eth.serotine.debug`) |
| `desktop-app` | the desktop JVM host and the jpackage configuration for the three installers |
| `ios-app` | the iOS host: the SerotineKit framework (its composition root) and the Xcode shell's XcodeGen spec (`xcode/`) |

The dependency rule, the MVVM contract and how to add a feature:
[docs/architecture.md](docs/architecture.md). How keys are held, what the
user chooses between, and what a hardware key can and cannot guarantee:
[docs/keys-and-signing.md](docs/keys-and-signing.md). The rules every change
goes through (review, PR, CI, release): [CLAUDE.md](CLAUDE.md).

## Releases

Every `v*` tag publishes a pre-release with the signed Android APK, the debug
APK, the macOS dmgs (arm64 + x64), the Linux debs and the Windows msi. The
tag's version must match the `version` in `build.gradle.kts`
(release-version-guard.yml refuses otherwise), and the Android release key
lives only in the `android-release` environment
([docs/android-release-signing.md](docs/android-release-signing.md)).

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
