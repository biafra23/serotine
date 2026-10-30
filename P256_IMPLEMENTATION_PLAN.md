# P-256 Safe Owner Implementation Plan

Status: branch `feature/p256-key-generation`, PR #182 (single-PR work item, base `main`).
Rewritten 2026-10-27 to cover the full feature end to end, with the owner rulings of
that day recorded. The architecture sections are the contract a reviewer holds the
diff against; the rulings below amend this file in place, not `CLAUDE.md` (that
would be a separate owner decision).

## Owner rulings (2026-10-27)

1. **Lazy contract deployment.** The per-owner signer contract is deployed only when
   it is first needed — never at key creation, and never merely because the key was
   made an owner. "Needed" means: a Safe is about to send a transaction whose
   signature set contains this owner's WebAuthn signature, so the Safe will verify it
   through EIP-1271 at the owner's address and needs code there. Before such a send,
   the app checks for code at the owner's address (a verified read through the engine)
   and, when there is none, sends the factory deployment transaction first, waits for
   its receipt, and only then sends the Safe transaction. The send flow shows the
   deployment as its own step and its gas in the review; the executing account pays it.
2. **Cheapest path for multiple P-256 owners.** One minimal proxy per owner, deployed
   by the pinned `SafeWebAuthnSignerFactory` via CREATE2 (that is the safe-modules
   pattern). With ruling 1 the cost per key is: zero for a key that never signs, one
   small deployment the first time it signs, nothing afterwards. N owners are N
   proxies; there is no shared deployment to save by batching, so deployments are
   separate transactions. The alternative — `SafeWebAuthnSignerSingleton`, whose
   signature carries the key and which needs no per-owner deployment at all — is
   recorded as the cheaper shape and re-evaluated when the verifier decision (open
   question 2) lands; until then this plan builds the lazy per-owner proxy.
3. **addOwner and swapOwner.** The Safe's on-chain calls are unchanged
   (`addOwnerWithThreshold`, `swapOwner` — Safe verifies owner signatures through
   EIP-1271 natively). "If necessary" resolves to: the *app's* flows change. The
   Safe-settings flows (`SafeSettingsViewModel`, `SettingsChangeKind.ADD_OWNER` /
   `SWAP_OWNER`, domain `SafeSettingsChanges`) gain: a held hardware-bound account can
   be chosen as the added or swapped-in owner, its owner address being its CREATE2
   proxy address (below); and the threshold warning (ruling 4) in the owners-and-
   threshold review. A lazily-deployed owner needs no deployment to be added — only
   when it first signs (ruling 1), and that includes settings transactions: a settings
   change signed by a P-256 owner goes through the same check-and-deploy step.
4. **Hardware-only threshold warning.** Whenever owners and threshold are reviewed —
   Safe creation and the Safe-settings changes — and the threshold can be met by the
   hardware-bound P-256 owners alone (threshold ≤ number of hardware-bound owners),
   the review shows a display-ready, non-blocking warning: those device keys alone can
   spend everything, so a Safe holding value wants at least one owner whose key is
   held elsewhere. This implements the standing rule that a hardware P-256 key is
   never the sole owner of a Safe holding value; it warns, it never blocks.

## Owner address — open question 1, resolved

The owner address is the CREATE2 address of the owner's signer proxy: computed
locally in `:domain` from the pinned factory address, the salt derived from the
key's (x, y) and the verifier address, and the factory's init code. This replaces
the keccak-of-public-key derivation in `NewHardwareP256Address` — that derivation
cannot ever hold code, which is what EIP-1271 verification needs at the owner
address. Keys already created on this branch keep their hardware key; no on-chain
migration is needed (no proxy was ever deployed, nothing on chain references the old
address), but recorded debug-build accounts are re-derived at first use after the
change.

## Still open — owner decisions, recorded here before their code lands

2. **Verifier contract, per network.** The CREATE2 address depends on it, so this
   blocks the address computation and the factory pinning. Proposal: the verifier
   safe-modules uses (FCL-style P-256 verifier), with `SafeWebAuthnSignerFactory` and
   `SafeWebAuthnSignerSingleton` addresses, pinned per network like Safe's own
   deployments and proven by their code in the pinning PR.
3. **Biometric policy for the enclave key.** Invalidate on re-enrolment
   (`.biometryCurrentSet`) or fall back to the device passcode. Blocks finalising the
   macOS/iOS key paths, not the Safe integration.

## Landed on this branch (PR #182 so far)

- `AccountKind.SAFE_P256`, `AccountCustody.HardwareBound` (domain `Account.kt`).
- `WebAuthnSignature` (domain `model/WebAuthnSignature.kt`: format, low-s check,
  `encode()`), `SafeSignature.WebAuthn` (domain `SafeSignatures.kt`), wire + codec
  support (`data/safe/SafeTxWire.kt` `WireSignatureWebAuthn`,
  `JsonSigningRequestCodec` version 2).
- `KeyStore.newHardwareP256Key()` + `NewHardwareP256Key` (domain
  `repository/KeyStore.kt`), implemented per platform: macOS Secure Enclave via JNA
  (`MacSecurity`, attributes fallback for enclave keys), Android Keystore
  (`EnvelopeKeyStore.Android.kt`, DER decoding + `DerPublicKeyDecoderTest`), iOS
  (`EnvelopeKeyStore.Ios.kt`, `IosSecurity.kt` — thin, completed in Phase C).
- Key creation and address display flows: `NewHardwareP256Address`,
  `NewHardwareP256Account` (domain use cases), Create-account integration
  (`CreateAccountScreen/ViewModel`, accounts menu, `SerotineIcons.HardwareP256`,
  Android debug deeplink), Safe-creation owner selection (`CreateSafeViewModel`).
- SafeTx display of WebAuthn signatures (`SafeTxViewModel`), and
  `SafeTxSigning`'s notes on where WebAuthn verification happens.

## Remaining work

- **Phase A — signer identity.** CREATE2 proxy-address computation in `:domain`
  (keccak + factory + salt + init code; no new library), replacing the keccak
  derivation; unit tests with known safe-modules vectors.
- **Phase B — pins.** Factory, singleton and verifier addresses for Ethereum, Gnosis
  Chain and Sepolia, each entry proven by its code, after open question 2 is decided.
- **Phase C — signing.** `HardwareSigner` port in `:data` and platform
  implementations: Android (Keystore ECDSA P-256 + BiometricPrompt), macOS (Secure
  Enclave via `MacSecurity`, LAContext), iOS. WebAuthn formatting: authenticatorData
  (rpIdHash, flags), `clientDataFields` with the SafeTx hash as challenge, r‖s (low
  s) → `WebAuthnSignature`. Open question 3 decides the access-control flags.
- **Phase D — lazy deployment.** `DeployWebAuthnSigner` use case in `:domain` over
  the engine: code-at-address check, factory deployment, receipt. Hooked into every
  Safe send that carries a P-256 owner's signature (transactions and settings
  changes), as ruling 1 spells out.
- **Phase E — addOwner/swapOwner.** The Safe-settings changes per ruling 3, plus the
  threshold warning per ruling 4 in `SafeSettingsViewModel` and
  `CreateSafeViewModel` (Safe creation).
- **Phase F — UI finish.** The signing prompt (biometric/passcode per the key's
  policy), custody labels in accounts list/detail, previews for every new state per
  the previews rule, screenshots in PR #182.
- **Phase G — tests.** Use-case tests (address computation vectors, deployment
  decision, threshold warning), signer unit tests, codec round-trips, UI tests;
  `./gradlew build` green.

## Before the final review of PR #182

- The debug `println`s go out of `MacSecurity.kt` and the `NewHardwareP256*` use
  cases.
- The PR description gets the screenshots per the screenshots rule and this plan's
  phase summary.
- Internal review of the whole diff (`/code-review`), findings addressed, then the
  PR waits for review comments — the merge is the owner's, as always.