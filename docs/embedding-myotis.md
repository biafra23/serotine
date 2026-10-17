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
version. On Android, Serotine takes JNA's classes from the libsodium bindings'
JNA AAR instead of the engine's jar (the two would duplicate them), and the app
re-states the strict pin as a constraint (`android-app/build.gradle.kts`). Entry point: `io.myotis.engine.Myotis` (`isAvailable()`,
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
  background, as in the Myotis app. Until then the one background run is the
  daily network-upgrade check (*Network upgrades*, below): a JobScheduler job
  that holds the engine awake for five minutes.
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
  nothing blocks a main thread. The desktop stops it after its window closes.
  Android pauses its networking 30 s after nothing holds it any more (a
  visible activity, the daily network-upgrade check) and resumes it with the
  next hold; `AndroidDnsServers` supplies the active network's resolvers.
- **Readiness, as Myotis reads it.** The adapters read `ChainHandle.status()`,
  a snapshot that never blocks, the way Myotis's own readiness ladder does:
  `SYNCING` is the checkpoint bootstrap; `CATCHING_UP` walks sync-committee
  periods from `syncStartPeriod`; `SYNCED` serves reads only with a verified
  head younger than 45 s and at least one peer serving state proofs.
- **Verified balances.** `VerifiedNativeBalances`, common code, proves a
  balance through `ChainHandle.requestAccount` whenever the verified head has
  moved. It is accepted only with no `failReason` and a `verifyMethod`, and is
  labelled with the block the proof anchors to: `VerifiedReads.getBalance`
  answers a block number from the current head, so it cannot be labelled. While
  reads cannot be verified, the row shows why ("Syncing…", "Sync refused", "Not
  verified", "No light client"), never an older amount. Why a node is down is
  written to stderr, with the engine's own log when it refused to start.
- **Verified token balances.** `VerifiedTokenBalances` runs each token's
  `balanceOf(holder)` through `VerifiedReads.callDetailed`, polled and cached
  like the rest, through `ProvenReads`. It is labelled with the block the
  engine names (`-1`, none, when it does not). `REVERTED` is a verified "not a
  token". `REFUSED`, and `INFEASIBLE` for a plain call, are a permanent refusal
  (`ReadUnavailable.REFUSED`, "cannot be read on this device"). `UNAVAILABLE`
  is unproven, retried at the next head.
- **Verified token metadata.** `VerifiedTokenMetadata` reads a contract's
  `decimals()`, `symbol()` and `name()` the same way, for the check of a
  built-in entry and for Add token, labelled with the block of the
  `decimals()` call. `decimals()` and `symbol()` must answer: a revert, an
  answer that is not a number or an ABI string, or decimals above 77 is a
  verified "not a token". A missing `name()` is the symbol. A refused call is
  `REFUSED` at once, without the calls after it. A description and a refusal
  are kept for the rest of the process (the 256 most recent): a token's
  description is read once, not at every head. "Not a token" ends the read
  but is not kept, since the contract may not be deployed at that head yet.
  Text a contract returns loses, code point by code point, whatever renders
  as nothing or moves text: controls, separators, format characters of any
  plane (bidi overrides, zero-width ones, tags), the rest of Unicode's
  default-ignorable set (variation selectors, the Hangul fillers) and the
  blank Braille cell, so none can reorder a line or hide in a symbol.
- **Verified prices.** `VerifiedPriceFeeds` reads each pinned Chainlink feed
  (`ChainlinkFeeds`, per network) with two verified calls, `decimals()` and
  `latestRoundData()`, through `ProvenReads`, at every verified head. Decimals
  other than the pin, a negative or zero answer, or what is no round are no
  price; a refusal is `REFUSED`. A round's age is measured against the
  time of the verified head the read started at, which the engine snapshot
  derives from the BLS-attested slot (genesis + slot × seconds per slot),
  never the device's clock; with no head time known yet the read is
  unproven. A round older than its feed's heartbeat and ten minutes' grace
  is stale, and one dated more than two minutes after the head is no feed's
  answer. (`ChainHandle.getBlockVerified`, which would give a block's
  timestamp, is not available on the Rust engine.)
- **One fetch per key and head.** `ProvenReads` keeps the last proof per key,
  and a subscriber that arrives while that key's proof for its head is being
  fetched waits for that fetch rather than making the same blocking calls:
  the account screen, the Assets screen and every account of the portfolio
  read the same prices and balances.
- **Verified address kinds.** `VerifiedAddressKinds` reads what is at an
  address for the Watch screen, polled, cached and refused the same way (the
  two share `ProvenReads`). The code hash comes from `requestAccount`'s proof,
  labelled with its block. The empty hash means an EOA. Any other hash fetches
  the code with `VerifiedReads.getCode`, which the engine checks against a
  proven hash too. The adapter accepts the code only if it hashes to the
  proof's value: the head may move between the two reads. An EIP-7702
  delegation designator (`0xef0100` and 20 bytes) makes a delegated EOA, and
  any other code makes a contract — or a Safe, below.
- **Verified Safes.** Code whose Keccak-256 is a pinned SafeProxy runtime
  hash (`SafeDeployments`, per network) is read further, with storage proofs
  (`ChainHandle.getStorageProof`, a mapping entry by its key):
  - slot 0 must name a pinned canonical singleton, whose version it takes;
  - the owners are the list of the mapping at slot 2, walked from the
    sentinel `0x1` back to it, as many as slot 3 counts;
  - the threshold (slot 4) is between 1 and that many;
  - slot 5 holds the nonce, and slot 1's list the modules;
  - the guard is the word at `keccak256("guard_manager.guard.address")`, and
    1.5.0's module guard the one at
    `keccak256("module_manager.module_guard.address")`.

  A slot proof is labelled with the beacon anchor it matched (the optimistic
  or finalized block; for a header-chain match, the block walked from), never
  with a peer's reported head. Every proof must anchor to one block, which
  labels the reading; a read the head moved under is made again at the next
  head. The guards sit at hashed slots, which `getStorageProof` cannot name,
  so they are verified `VerifiedReads.getStorageAt` reads at the head: they
  may be a head later than the label. A list that does not lead back to the
  sentinel, an unknown singleton or a Safe not set up is "another contract".
  A Safe takes a dozen proofs and its owners rarely change, so its reading
  stands for five heads (about a minute) before it is proven again, always
  labelled with its own block. The pins were read on Sepolia on 2026-10-17:
  each singleton's `VERSION()` as a verified call, each proxy hash from a
  verified create call of its factory's `proxyCreationCode()`.
- **The light client's progress.** `EngineLightClient` drives the
  `LightClient` port, and the Assets tab shows it in a strip under the top bar:
  - starting;
  - bootstrapping from the checkpoint;
  - verifying sync-committee periods (*n of m*);
  - waiting for a fresh head or for proof peers;
  - "Verified at block N";
  - asleep, a stale anchor, or no engine.

  A cold start on Sepolia, measured on a Mac: bootstrapped after 1 s, verified
  reads after about 20 to 30 s.
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

## Sending

Part 11 of `feature/wallet-ux` adds the engine's half of a transfer: four
adapters over `VerifiedReads` and `EnsApi`, behind domain ports, all on a
`TransactingChain` seam that extends `VerifiedChain`. The screens come with
the next part. Every read is made only while the light client can verify reads
at its head; the broadcast is the engine's to judge (it does so on verified
state). All of it runs on the adapters' dispatcher, and an engine that fails,
or a `CancellationException` its own code throws, is no answer, as for every
verified read.

- **`EngineTransactionInputs`** (`TransactionInputs`):
  - The nonce is `getTransactionCount(address, "pending")`: the verified count
    plus the sends this node made that are not mined yet, so a second transfer
    right after a first does not reuse its nonce. That record is the engine's
    memory, gone with the process and expiring; the app's own record of sent
    transfers comes with the Send screens.
  - The fee levels come from `feeHistory` over the last ten blocks at the 10th,
    50th and 90th percentile (`EngineJson.feeQuote`). Each level's tip is the
    median of its percentile, never below 0.001 gwei and never below the
    slower level's. The next base fee is the history's last; the fee cap is
    twice that plus the tip.
  - The gas is `estimateGasDetailed`, so a revert, an infeasible transfer and
    a refusal are told apart. It is never taken below the transaction's
    intrinsic gas (21000 plus Prague's calldata floor): probed on Sepolia, the
    Rust engine estimates a zero-value transfer at 17433, which every node
    would refuse.
  - The simulation is `callDetailed` from the sender at the head, the transfer
    as it will run.
- **`EngineBroadcaster`** (`Broadcaster`): `sendRawTransactionDetailed`. The
  engine judges a send on verified state before it gossips it, and its verdict
  (sent, rejected with geth's reason, refused, unavailable) is passed on.
- **`EngineReceipts`** (`ReceiptTracker`): `getTransactionReceipt` every poll,
  verified against the including block's receipts root. A receipt at or below
  the engine's finalized block (`StatusSnapshot.finalizedBlockNumber`) ends the
  watch as finalized.
- **`EngineNames`** (`NameResolver`): `ens().resolveAddress(name,
  EnsRoot.FINALIZED)` and nothing fresher. `PEER_HEAD` would be a peer's claim,
  and `AUTO` falls back to it, so an answer not `verified` is no answer.
  - The engine is built without a CCIP-Read gateway (`EnginePorts.httpGateway`
    null). A name whose resolver answers through one (EIP-3668) is off-chain
    data no ruling allows, and the request would tell the gateway the name
    and the device's address. The engine then refuses the name, and it reads
    as `OffchainOnly`, not as "try again".
  - The engine lower-cases a name and normalizes nothing else (no ENSIP-15),
    so a look-alike (a Cyrillic letter, a zero-width joiner) would hash to
    another node. Until normalization exists, only ASCII letter-digit-hyphen
    labels are resolved.

The transaction itself is the domain's: `Eip1559Transaction` builds the
signing digest the signer signs and the raw bytes the broadcaster sends, and
`QuoteTransfer` and `SendTransfer` (`:domain`) put the four together with the
balances and the signer. What is signed is exactly what the quote shows: only
`QuoteTransfer` makes a quote, and nothing copies it. A transfer signed but not
broadcast keeps its signed bytes (`SignedTransfer`), and a retry
(`RebroadcastTransfer`) sends those bytes again rather than quoting anew, which
could take the next nonce while the first is still on its way.

## Network upgrades

A hard fork the engine's build does not implement is the one thing that ends
a working install: past it, the light client can no longer verify the chain
(the sync committee signs under the new fork's version), and the wallet would
look stuck syncing. Myotis detects one ahead of time and Serotine tells the
user to update — before it, and, after it, nothing else.

- **What Myotis reports.** `StatusSnapshot.upgradeAdvisory()`: an
  `UpgradeAdvisory` with a phase (`SCHEDULED`, its activation ahead;
  `ACTIVE`, passed), the activation time (0 when unknown), the fork id
  upgraded peers use (`0x00000000` when unknown) and how many distinct peer
  networks back it. Two detectors feed it: the execution layer's EIP-2124 fork
  ids in peers' eth Status (enabled on Sepolia) and the consensus layer's ENR
  `eth2` fields and Status digests (every network), each judged against the
  build's own fork schedule, with one vote per IPv4 /24 or IPv6 /48, at least
  three sources and more than those contradicting it. It is reported in every
  lifecycle state, asleep included. Upgraded clients announce a fork from the
  day their release carries it, so the warning comes weeks ahead.
- **It is peer data, never verified.** Lying peers can raise a false warning,
  never a wrong answer: nothing in verification reads it. So Serotine shows
  it as what peers report, and claims it can no longer follow the network
  only when the light client's own state agrees — not synced, or synced with
  a stale head — the rule Myotis sets for every host (CLAUDE.md, *Trust*).
- **In Serotine.** `EngineNetworkUpgrades` (`eth.serotine.data.engine`) is the
  `NetworkUpgrades` port over it, polled with the engine's status; a malformed
  field becomes unknown rather than failing the status poll the light client
  and every verified read share. `ObserveUpgradeWarning` (`:domain`) joins it with the light
  client's state and the upgrade recorded from earlier reports:
  - *Ahead*: a banner on Assets, with the date and the way to the update;
  - *Activated*, as peers report or by its announced time on the clock, the
    light client not showing this version cut off: the banner says so;
  - *Update required*, an activation peers confirmed (reported now, or
    recorded as active) and a light client that is not verifying: the app
    shows the update screen and nothing else, the back stack cleared, until
    the light client verifies again (or the app is updated). A recorded time
    that merely passed on the clock never locks the app: a fork can move.
- **Notifications.** `UpgradeNotices` (`:domain`) shows a notice when peers
  first report an upgrade, a week and a day before its activation, and once
  when it has activated; then nothing more. Each is shown once, the most
  urgent when several fall due together. The record that remembers this
  (`FileKnownUpgradeStore`, `eth.serotine.data.upgrade`, `upgrade.json` next to
  the account list) belongs to one app version: an updated app starts with
  none, and its own engine says whether its build supports the upgrade. An app
  opened after an activation peers confirmed knows it from the record at once,
  before peers report it again. A changing count of reporting networks is not
  written. A notice is shown before the record says it was: told twice
  (should the app die in between) beats never. The activation notice and the
  update screen say "peers report" only when they did; one due by the clock
  alone says the announced time has passed.
- **While the app is closed.** Android: a daily JobScheduler job (any network,
  battery not low) wakes the engine for five minutes, long enough for peers to
  report an upgrade, and no longer once peers confirmed an upgrade activated
  and the user has been told (all is said; only an update changes it); an
  inexact alarm (`setAndAllowWhileIdle`, no exact-alarm
  permission) shows each reminder at its time, re-armed after a reboot or an
  update; the notification permission is asked for at launch (Android 13 and
  later; Android stops asking after two refusals), and without it the app's
  own screens still say everything. The
  desktop notifies through the system tray while it runs; a reminder that fell
  due while it was closed is shown at its next start.

## What the engine's rules mean for a wallet

- **Every chain read is verified or refused.** The engine answers from the
  verified head; when it cannot prove an answer it fails the call. Serotine
  shows that state (`CATCHING_UP`, `STALE_ANCHOR`, an unproven read) instead
  of filling in from anywhere else. There is no RPC-provider fallback, in
  any build type.
- **A parameter that can change the answer is applied or refused**, never
  accepted and ignored — the Myotis rule, and the reason a port's contract
  spells out what each argument does.
- **Not everything a wallet needs is chain data**, and what is not is a
  separate decision for the owner — what is fetched, over what, and how its
  answer is trusted or shown as untrusted — recorded in CLAUDE.md under *Data
  sources* before the code lands. Prices, the token list and Safe
  co-signing are decided there (2026-10-16), each without an off-chain
  service; the dApp connection protocol is still open.
