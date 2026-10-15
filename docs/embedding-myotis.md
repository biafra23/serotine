# Embedding Myotis

Serotine reads the chain through an embedded [Myotis](https://github.com/biafra23/myotis)
engine — a sync-committee light client that verifies every answer against
Ethereum's consensus, so the wallet never trusts an RPC provider. This page is
how that engine reaches the app and the rules around it. The library landed in
Myotis with PR [#654](https://github.com/biafra23/myotis/pull/654) (merged
2026-10-15); its artifacts are published by the first Myotis `v*` tag after
it, which is when the coordinates below resolve.

## The library

| artifact | what |
|---|---|
| `eth.myotis:myotis-engine:<version>` | the root: Gradle module metadata that picks `-android` (an AAR with the Rust engine's native libraries for `arm64-v8a` and `x86_64`) for an Android consumer and `-jvm` for a desktop one |
| `eth.myotis:myotis-api` | the contract (`MyotisEngine`, `ChainHandle`, `VerifiedReads`, the ports) — an `api` dependency of the engine, so it is on the consumer's compile classpath |
| `eth.myotis:myotis-engine-ports` | the default port actuals (node key file, peer caches, per-network file layout) — the other `api` dependency |

Its whole third-party graph is JNA, slf4j and minimal-json; JNA is pinned
strictly to the native stub the AAR carries, so never force another JNA
version. Entry point: `io.myotis.engine.Myotis` (`isAvailable()`,
`newEngine()`, `defaultPorts(...)`); the consumer guide is
`docs/android-library.md` in the Myotis repository.

## Where it comes from

`settings.gradle.kts` already declares the token-free `maven-repo` branch of
biafra23/myotis as a Maven repository, filtered to the `eth.myotis` group.
Every Myotis `v*` tag merges its artifacts into that branch; versions
accumulate, and the per-artifact metadata is merged on each publish. The same
set is on GitHub Packages (a token with `read:packages`) and, per release, as
`myotis-engine-<tag>-maven-repo.zip` for a build that reaches neither host.
Verify downloads against the release's `myotis-engine.SHA256SUMS`. For a
consumer developed against an unreleased engine, Myotis's
`./gradlew publishToMavenLocal` publishes the set into `~/.m2` (add
`mavenLocal()`, filtered to `eth.myotis`, while that lasts — never commit it).

## Where it goes in Serotine

- **`:data` only.** The engine is a data source. An adapter in
  `eth.serotine.data.engine` implements the domain's ports (balances, nonces,
  logs, broadcast — each its own port) over `io.myotis.api`; `:domain` and
  `:ui` never import `io.myotis.*`.
- **`io.myotis.api` and nothing below it.** Engine internals are not on the
  classpath (the library does not ship them), and the Myotis rule carries over
  verbatim: a host talks only to the API contract. The library's default
  ports (`io.myotis.engine.ports`, `Myotis.defaultPorts`) are part of its
  public surface and may be used.
- **The composition roots own the engine's lifecycle.** One engine per process,
  hosting Sepolia and no other network for now (CLAUDE.md, *Data sources*),
  created where the `AppGraph` is built, with the engine's state directory
  under the app's private files (Android: `filesDir/myotis`; desktop: the
  app's data directory). Each host supplies its platform's ports (on Android,
  the DNS servers of the active network, as the consumer guide shows).
- **Android:** the `:data` module's Android target is what resolves the AAR;
  minSdk 29, two ABIs — a build that ships other ABIs has no engine there.
  The engine's start belongs in a foreground service once sync runs in the
  background, as in the Myotis app.
- **Desktop:** the JVM jar does not carry the host library; the desktop
  packaging stages it into the app's resources, as Myotis's own desktop app
  does (`appResourcesRootDir`), one per OS/arch leg of the installer
  workflows.
- **iOS** consumes the engine's plain C ABI, as Myotis's `:app-ios` does;
  that is the iOS part of the plan, not this page.

## What exists today

- **`MyotisNode`** (`eth.serotine.data.engine`, in `:data`'s `jvmSharedMain`)
  is the process's one engine on Sepolia. Each composition root creates it with
  `MyotisNode.start(network, dataDir, dnsServers)`. Loading the native library,
  creating the engine and starting it all run on a background thread, so
  nothing blocks a main thread. The desktop stops it when its window closes.
  Android keeps it for the life of the process, with `AndroidDnsServers`
  supplying the active network's resolvers; a foreground service comes with
  background sync.
- **Verified balances.** `VerifiedNativeBalances`, common code, drives the
  `NativeBalances` port: it polls the light client's state and reads a balance
  at exactly the verified head, again only when the head moves. A syncing
  client, a stale anchor, an unproven read and a device without an engine are
  readings the account list shows ("Syncing…", "Sync refused", "Not verified",
  "No light client"), never a number from elsewhere. Why a node is down is
  written to stderr, with the engine's own log when it refused to start.
- **The light client's progress.** `EngineLightClient` drives the
  `LightClient` port from `ChainHandle.beaconStatus()`, and Home shows it in a
  strip under the top bar: starting, bootstrapping from the checkpoint,
  verifying sync-committee periods (*n of m*, counted from the first period
  seen after the bootstrap), collecting verified state roots, then "Verified at
  block N". A stale anchor and a missing engine are shown there too. A cold
  start on Sepolia, measured on a Mac: bootstrapped after 1 s, verified reads
  after about 30 s.
- **Resolving the library before a Myotis tag publishes it.** In a Myotis
  checkout, `./gradlew -PpublishRelease publishToMavenLocal` puts the version
  the next tag publishes into `~/.m2`. Pass Serotine's builds an init script
  that adds `mavenLocal()` filtered to `eth.myotis`, with `-I`; the repository
  itself never names `mavenLocal()`:

  ```kotlin
  // myotis-local.init.gradle.kts, kept outside the repository
  settingsEvaluated {
      dependencyResolutionManagement.repositories.mavenLocal {
          content { includeGroup("eth.myotis") }
      }
  }
  ```
- **Desktop, until the installers stage the host library.**
  `./gradlew :desktop-app:run -PmyotisLibraryDir=<dir>` puts a locally built
  `libmyotis_engine.{dylib,so,dll}` on `java.library.path` (Myotis:
  `./gradlew cargoBuildHost`, then `rust/target/release`). The same property
  runs `MyotisNodeSepoliaTest`, a real-network test that is skipped otherwise.
- **Android 10 and INTERNET.** An install that gains INTERNET on an update,
  rather than having it from the first install, can keep getting `EPERM` from
  netd's eBPF socket filter until it is reinstalled or the device reboots. The
  engine then reports "listen failed" and the list says "No light client". No
  released Serotine was ever without the permission, so this is a development
  trap, not a user path.

## What the engine's rules mean for a wallet

- **Every chain read is verified or refused.** The engine answers from the
  verified head; when it cannot prove an answer it fails the call. Serotine
  shows that state (`CATCHING_UP`, `STALE_ANCHOR`, an unproven read) instead
  of filling in from anywhere else. There is no RPC-provider fallback, in
  any build type.
- **A parameter that can change the answer is applied or refused**, never
  accepted and ignored — the Myotis rule, and the reason a port's contract
  spells out what each argument does.
- **Not everything a wallet needs is chain data.** Safe{Wallet} multi-signer
  flows (proposing a transaction to co-signers) and token metadata are
  off-chain services. Each one is a separate decision for the owner — what is
  fetched, over what, and how its answer is trusted or shown as untrusted —
  recorded in CLAUDE.md under *Data sources* before the code lands.
