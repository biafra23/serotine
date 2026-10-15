# Serotine

A Compose Multiplatform wallet for Ethereum accounts (EOAs) and
[Safe{Wallet}](https://safe.global) smart accounts, built on an embedded
[Myotis](https://github.com/biafra23/myotis) engine: every chain read is
verified against Ethereum's sync committee by a light client running inside
the app, with no RPC provider in the loop. Android and desktop (Linux, macOS,
Windows) today; iOS next.

**Status: skeleton.** The module graph, the MVVM/clean-architecture seams, the
tests and the release workflows are in place; the wallet features and the
Myotis engine integration come next (docs/embedding-myotis.md).

## Build & run

```bash
./gradlew build                          # every module: compile, unit + UI tests, Android lint
./gradlew :desktop-app:run               # the desktop app, in-process
./gradlew :android-app:installDebug      # the debug app (eth.serotine.debug) on a connected device
./gradlew :desktop-app:packageDeb        # the Linux installer   (on Linux)
./gradlew :desktop-app:packageDmg        # the macOS installer   (on macOS)
./gradlew :desktop-app:packageMsi        # the Windows installer (on Windows, WiX 3 on the PATH)
```

Needs JDK 21 and, for the Android modules, an Android SDK (`ANDROID_HOME`, or
`sdk.dir` in a git-ignored `local.properties`). The iOS targets of the shared
modules build on macOS only and are skipped elsewhere.

## Layout

| module | role |
|---|---|
| `domain` | entities, value objects, ports (repository interfaces) and use cases — pure Kotlin |
| `data` | the adapters behind the ports: today an in-memory account store, next the Myotis engine |
| `ui` | the shared Compose Multiplatform UI: screens, ViewModels, UI state, `AppGraph` |
| `android-app` | the Android host (package `eth.serotine`; the debug build is `eth.serotine.debug`) |
| `desktop-app` | the desktop JVM host and the jpackage configuration for the three installers |

The dependency rule, the MVVM contract and how to add a feature:
[docs/architecture.md](docs/architecture.md). The rules every change goes
through (review, PR, CI, release): [CLAUDE.md](CLAUDE.md).

## Releases

Every `v*` tag publishes a pre-release with the signed Android APK, the debug
APK, the macOS dmgs (arm64 + x64), the Linux debs and the Windows msi. The
tag's version must match the `version` in `build.gradle.kts`
(release-version-guard.yml refuses otherwise), and the Android release key
lives only in the `android-release` environment
([docs/android-release-signing.md](docs/android-release-signing.md)).

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
