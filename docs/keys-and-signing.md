# Keys and signing

Serotine holds private keys; Myotis never does. This page is the design for
how they are held, what the user chooses between, what a hardware-backed key
can and cannot do for a wallet, and the recommendations that came out of the
owner's key-storage discussion of 2026-10-15. **This page is the contract**
for the key-store and signing adapters; the *Trust* section of CLAUDE.md is
the summary a reviewer holds a diff against. Platform facts below were
checked in October 2026 and carry their sources at the end; re-check them
before relying on one in code.

## What the platforms can hold

| platform | hardware key store | curves and algorithms | binding a key to the user |
|---|---|---|---|
| Android (minSdk 29) | Keystore in the TEE; StrongBox on devices with a secure element | TEE: P-256/384/521, Curve25519 from API 33, RSA, AES, HMAC. StrongBox: P-256, RSA, AES, HMAC only | per-use or timed authorization, strong biometrics and/or device credential; invalidation on biometric re-enrollment |
| iOS, iPadOS | Secure Enclave | P-256 only (ECDSA, ECDH); ML-KEM / ML-DSA from iOS 26 | current biometric set, passcode, application password; this-device-only accessibility classes |
| macOS | Secure Enclave on Apple silicon and T2 Macs, same API | as iOS | as iOS |
| Windows | TPM 2.0 through CNG's Platform Crypto Provider | RSA, ECDSA P-256/P-384 | Windows Hello |
| Linux | TPM 2.0 through tpm2-tss where one exists; often nothing | per TPM | nothing an app can rely on |

**No platform holds a secp256k1 key in hardware, and none exports a key it
generated.** Android's secure key import only goes inward; the Secure Enclave
has no import at all. Samsung's Blockchain Keystore does hold secp256k1 inside
Knox, but it is Galaxy-only, partner-gated and last released in December 2023;
it is not a basis for a cross-platform wallet. Two consequences follow:

- An **EOA key**, and a Safe owner key derived from a mnemonic, is a software
  key. The most the hardware can do for it is wrap it at rest and gate its
  use. That is the envelope below, and there is no more secure way to hold a
  secp256k1 key on these platforms; the security gains are in the details.
- A **P-256 key born in the Secure Enclave or StrongBox can own a Safe
  directly**, through Safe's WebAuthn signer contracts. For such an account
  no secp256k1 key exists anywhere.

## The account tiers

| tier | key | backup | daily unlock | status |
|---|---|---|---|---|
| **Hardware-bound Safe owner** | P-256 in the Secure Enclave / StrongBox | none: it is a device credential | the OS's own gate, bound to the key and chosen at creation: current biometrics only, or biometrics or device credential. This tier has no password factor | decided (2026-10-15); the key's policy is under *A Safe owned by a hardware P-256 key*, and it needs a recovery owner, see *Backup and recovery* |
| **Mnemonic key** | secp256k1 from a BIP-39 seed, in the envelope | the mnemonic, written down once at creation | password; biometrics if the user enables them | decided (2026-10-15); every EOA, and a Safe owner the user wants to be able to restore |
| **Synced-passkey Safe owner** | P-256 passkey through the platform WebAuthn API | synced end-to-end encrypted by iCloud Keychain / Google Password Manager | biometrics | recommended as an option; owner's decision open |

Which tier an account uses is the user's decision. The app explains the
trade when the account is created and never downgrades a tier on its own.

## The envelope: a secp256k1 key at rest

```
DEK          random 32 bytes; exists only in memory, only while unwrapping
blob         AES-256-GCM(DEK, the secp256k1 key or the BIP-39 seed)

password path (always present)
  HW_pw      a hardware key with no user-auth requirement
             (Android: an HMAC-SHA256 key in the Keystore, StrongBox when
              available; iOS: a Secure Enclave P-256 key used through ECIES
              or ECDH, the enclave has neither HMAC nor AES)
  P          Argon2id(password, salt, params)
  S_hw       Android: HMAC(HW_pw, P), computed per unlock, never stored
             iOS:     random 32 bytes, stored wrapped by HW_pw
  KEK_pw     HKDF( P || S_hw )
  stored     AES-256-GCM(KEK_pw, DEK) with salt and params as its AAD;
             the salt; the Argon2id parameters

biometric path (optional, the user's choice)
  HW_bio     a second hardware key that requires per-use strong-biometric
             authorization
  stored     wrap(HW_bio, DEK)
  disable    delete HW_bio and this wrapped copy; the password path is untouched
```

The rules a reviewer holds the adapter against:

1. **Both factors are necessary, cryptographically.** The password is mixed
   into the key-encryption key, never merely checked. A password that is only
   compared, with the hardware key doing the real decryption, is bypassed by
   anything running inside the app's process that can call the key store. And
   the hardware secret `S_hw` is mixed in too, so a blob copied without the
   device is useless and cannot be brute-forced at all.
2. **The password is stretched with Argon2id** (scrypt is the acceptable
   fallback where no Argon2 is available), and the threat it answers is
   precise. A blob copied without the device — a backup, a file-system dump —
   cannot be attacked at all, because `S_hw` is missing. A compromise inside
   the app's process can call `HW_pw`; on iOS that yields the static `S_hw`
   once, and with the blob the password is then brute-forced *offline*, on
   GPUs. Argon2id's memory-hardness is what makes that expensive, so its
   parameters are chosen against that attacker, not for feel: the largest
   memory the slowest supported phone tolerates, never below 64 MiB, and the
   time that costs, up to about a second. On Android the hardware is made
   necessary for every guess instead: `S_hw` is `HMAC(HW_pw, P)` with `HW_pw`
   a Keystore HMAC key, so each guess needs a hardware round trip and the
   search cannot leave the device. The Secure Enclave has no HMAC, so on iOS
   the KDF carries the load; the enclave's own answer is the
   application-password flag of rule 6. The salt and the Argon2id parameters
   are stored next to the blob and bound as the additional authenticated data
   of the AES-GCM wrap of `DEK`, and the floor is re-checked when unwrapping:
   a parameter block below it is refused, not honoured, so tampering with it
   cannot downgrade the KDF.
3. **The envelope's biometric path binds to a key, not to the UI.** On
   Android: a key with `setUserAuthenticationRequired(true)` and per-use authorization
   (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` from API 30;
   on API 29 the `-1` validity-duration form), `setInvalidatedByBiometricEnrollment(true)`,
   `setUnlockedDeviceRequired(true)`, used only through a `BiometricPrompt`
   with a `CryptoObject`. Class 3 (`BIOMETRIC_STRONG`) only; a device with
   only weak biometrics gets no biometric path and is told why. On iOS:
   `SecAccessControlCreateWithFlags(kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
   [.privateKeyUsage, .biometryCurrentSet])` on a Secure Enclave key.
   `.userPresence` allows passcode fallback and is a different, weaker policy;
   if offered, it is offered by name. This is the envelope's second key,
   `HW_bio`; the hardware-bound Safe owner key has its own policy, below.
4. **Each path has its own hardware key.** Enabling biometrics never weakens
   the password path; disabling them is a deletion, not a flag.
5. **Verify hardware backing on Android.** `KeyInfo.getSecurityLevel()` (API
   31) or `isInsideSecureHardware()` before it, or the attestation chain.
   Emulators and some devices give a software key store; the user is told,
   and the app does not pretend otherwise. Prefer StrongBox
   (`setIsStrongBoxBacked(true)`) and fall back to the TEE on
   `StrongBoxUnavailableException`.
6. **Apple's application-password flag** (`.applicationPassword`, fed through
   `LAContext.setCredential`) is described by Apple as an additional
   encryption mechanism inside the enclave, not an access constraint. Developer
   reports show it misbehaving on Secure Enclave keys in some OS versions.
   It may be added as a further layer after testing on current iOS; it is
   never the foundation and never replaces rule 1.
7. **The plaintext window is tiny.** The secp256k1 operations run in native
   code (libsecp256k1 through JNI on Android and the JVM, cinterop on iOS),
   take the key as a byte array and zero it afterwards. The key never
   becomes a `String`, is never cached, never logged, never crosses a
   suspension point the UI owns. The password is different: Compose text
   input holds it as `String` state that cannot be zeroed, so the rule for it
   is scoped to what the adapter controls. It is converted to bytes at the
   port boundary, the bytes are zeroed after the KDF, and it is never
   retained, logged or cached beyond the field's transient state, with the
   field under rule 8's screen protections. Garbage-collected runtimes can
   copy memory; short exposure is the real mitigation, so the sign call is
   one call, not a session.
8. **The ciphertext never leaves the device through a backup or a transfer.**
   Android: `android:allowBackup="false"` AND `android:dataExtractionRules`
   with both a `<cloud-backup>` and a `<device-transfer>` exclusion for the
   key store's files — on Android 12 and higher `allowBackup="false"` alone
   still lets a device-to-device migration copy them — plus
   `fullBackupContent` for the API 29/30 path. The host's manifest has all
   three (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`), excluding
   the whole app's data. iOS: the
   this-device-only keychain classes, which neither sync nor migrate, and the
   blob in a file marked excluded from backup. Screens that show a seed or
   take the password: `FLAG_SECURE` on Android; on iOS, where no API blocks a
   screenshot, the window is covered when the app resigns active so the app
   switcher's snapshot holds nothing, and `UIScreen.isCaptured` plus the
   screenshot notification raise a warning; on desktop nothing comparable
   exists and the seed screen says so.
9. **Desktop stands on the password path alone where no hardware exists**
   (the owner's ruling of 2026-10-15, CLAUDE.md *Trust*).
   macOS on Apple silicon or T2 uses the Secure Enclave as iOS does, signed
   or not (the owner's decision of 2026-10-16); Windows
   wraps `S_hw` with a TPM key through CNG; Linux uses a TPM where present.
   That TPM path is not built yet: until it is, Windows and Linux stand on
   the password alone, a TPM present or not, and the app tells the user it
   does not use the TPM yet (the owner's ruling of 2026-10-16).
   Without hardware, `S_hw` is absent, the blob is brute-forceable offline,
   and the KDF cost goes up to compensate. The adapter reports which case the
   user is in.

### Where it lives in the architecture

Per docs/architecture.md: the key store is a data source, so the adapter is
`eth.serotine.data.keys`, with `expect`/`actual` for the platform seam in the
smallest source set that needs it. It implements small ports in `:domain`,
named for one concern each: a key-store port for the lifecycle (create,
import a mnemonic, enable or disable the biometric path, delete) and a signer
port for one thing, a signing request in and a signature out. `:ui` sees use
cases, never a key, as CLAUDE.md's *Trust* section already requires. A port
that can fail (locked, biometric invalidated, hardware missing) says so in
its KDoc and returns it as a result the UI shows as state.

**What exists today**: the key-store port
`KeyStore` and the signer port `Signer` in `:domain`, both implemented by
`EnvelopeKeyStore` in `eth.serotine.data.keys`.

- **Create.** `newMnemonic()` makes a 12-word BIP-39 mnemonic from 128 bits of
  the platform's secure randomness (`EnvelopeCrypto.randomBytes`), zeroing the
  entropy. Nothing is stored: the words travel to Create account's screen under
  the mnemonic exception, are shown there alone, three of them are asked back,
  and they are stored by the import below.
- **Sign.** `sign(account, digest, unlock)` opens the account's envelope
  (`<address>.seed`), with the password and the hardware secret
  (`Unlock.WithPassword`) or with the DEK the biometric path unwraps
  (`Unlock.WithBiometrics`, below), derives the first account's key and refuses
  a key that does not derive the account it is filed under. It signs the
  32-byte digest as given, never hashed again, with libsecp256k1 (a low s, RFC
  6979's nonce) and finds the recovery id by recovering the public key. The seed, the key and the password's bytes are
  zeroed before it returns (rule 7); a wrong password, a missing key and an
  envelope this device cannot open are results, never exceptions. Send
  (docs/wallet-ux.md, screens 7 to 9) is its first consumer: the review takes
  the password under rule 8's protections, or offers the biometric prompt where
  the path is on, and the signer signs the quoted transaction's EIP-1559 digest.
  A Safe transaction's approval or execution is such a transaction too.
- **What a Safe owner signs** (`SignSafeTx`; docs/wallet-ux.md, *Co-signing a
  Safe transaction*). The same `sign(owner, digest, unlock)`, with the Safe
  transaction's EIP-712 hash as the digest: computed here from the raw
  `SafeTx` fields, the Safe's address and the chain id (the domain of Safe
  1.3.0 on, the only versions the app recognises), and signed as it is, with
  no `eth_sign` prefix. The signature travels as `r ‖ s ‖ v`, `v` = 27 + the
  recovery id, which is what the Safe's `checkSignatures` and an EIP-712
  signer elsewhere produce for that hash. Before anything signs:
  - the owner is a current owner of the Safe, as its verified storage lists
    them, and the nonce is not one the Safe has passed;
  - a delegate call is refused: the Safe would run the target's code as
    itself, and no preview can say what that code does;
  - the key store's signature is recovered (`SignatureRecovery`, libsecp256k1)
    and kept only when it recovers to the owner, as a co-signer's is.

  Nothing about what is signed comes from the request's sender: the screen
  shows what the fields decode to (`SafeTxAction`), and the hash is this
  device's own. That is the independent second view of *What key storage does
  not do* below; it is still the app's view, and for a Safe holding value not
  the sole check. An owner may approve the hash on chain instead
  (`approveHash`, an ordinary transaction from its account), which needs no
  signature to travel at all.
- **Biometric unlock** (rules 3 to 5), the user's opt-in in Settings → Security,
  on Android:
  - `HW_bio` is `AndroidBiometricKey`: an RSA-2048 pair in the Keystore,
    StrongBox first and the TEE otherwise. Its private half decrypts only after
    a per-use strong biometric (`setUserAuthenticationParameters(0,
    AUTH_BIOMETRIC_STRONG)`, the `-1` duration on API 29), only on an unlocked
    device (`setUnlockedDeviceRequired`), and it is invalidated when the enrolled
    biometrics change. A key the Keystore does not keep in secure hardware is
    deleted, and the path is not offered (rule 5).
  - `enableBiometrics(password, confirmation)` opens each envelope with the
    wallet password to reach its DEK, makes the key anew and files each DEK
    wrapped by its public half (RSA-OAEP, SHA-256 with MGF1-SHA-1) as
    `<address>.bio`. Wrapping needs no biometric, so the path is then proven
    once: one wrap opened through the prompt must give back its DEK. Only then
    is it on; a dismissed or failed proof turns it off again. The key and the
    wraps are made as one step and undone together; a crash in between leaves
    wraps the next enable or disable sweeps away. A key imported while the path
    is on is wrapped too. The password path is untouched.
  - What the device cannot offer is said before the password is asked: no
    strong sensor, nothing enrolled, only weak biometrics, or a key store
    outside secure hardware (the password path's key tells, rule 5). While the
    biometric key exists the path reads as on, usable right now or not, so
    that it can always be turned off.
  - `sign(…, Unlock.WithBiometrics(reason))` decrypts the wrap through the
    platform's `BiometricPrompt` with the cipher as its `CryptoObject`, so only a
    match the hardware accepts unlocks it. The prompt is the host's
    (`PlatformBiometricPrompter`, on the activity in front), says the caller's
    reason, and offers "Use the password" as the way out.
  - `disableBiometrics()` deletes the key, then the wraps (rule 4). The last key
    deleted takes the path with it, as it takes the wallet password.
  - A dismissed or timed-out prompt, a key invalidated by re-enrolment, a
    locked-out or busy sensor and an account stored before the path was on are
    results the signing screen turns into "use the password".
  - The key store's calls take turns, `describe()` included: the biometric
    key's check opens a Keystore operation, which must not compete with the one
    a pending prompt holds (StrongBox has few).
  - The desktop and iOS have no biometric path yet, and their Security screen
    says so.

- **Import.** `importMnemonic(mnemonic, password)` checks the restore
  screen's words against the BIP-39 English wordlist and their checksum. It
  derives the first account, `m/44'/60'/0'/0/0` with the empty passphrase, on
  libsecp256k1 (ACINQ's secp256k1-kmp). The use case stores the key and then
  adds the account as one step that a cancelled caller cannot cut in half.
- **Storage.** The BIP-39 seed goes into the envelope above, one file per
  account (`<address>.seed`, JSON) in a directory of the app's own.
  - Writes go through a temporary file readable by this user alone, an
    fsync, an atomic move and, where the platform allows, an fsync of the
    directory. A temporary file a crash left behind is deleted on the next
    import.
  - Android keeps the directory out of backups and device transfers (rule 8).
    On macOS the desktop host excludes it from Time Machine. On Linux it
    marks it for GNOME's Backups (Déjà Dup), whose default takes the whole
    home directory, with a `.deja-dup-ignore` file. Windows' File History
    leaves application data out unless the user adds it. Other backup tools
    the user configures may still take it.
  - A file that cannot be read, that names another kind of hardware secret
    than this device's, that has parameters outside the floor and ceiling, or
    that is not under its own address's name, is reported and passed over,
    never trusted. (The tag names the kind, not the device: another phone's
    envelope passes this check and then fails as a wrong password.) A
    damaged envelope of the account being imported is sealed again.
- **Deletion.** `delete(address)` deletes the account's envelope when the
  account is removed from the wallet; the use case removes the account from
  the list first, so a failure in between leaves a key without an account
  (importing the phrase again finds it), never an account whose key is gone.
  It asks for no password: it takes nothing from someone who could clear the
  app's data anyway. It is a plain file delete, so the ciphertext may stay on
  the storage until the blocks are reused. With the last envelope the wallet
  password goes too, and the next import sets a new one. (Proposed with part 2
  of `feature/wallet-ux`: whether deleting a key needs the password first is
  the owner's ruling, recorded in CLAUDE.md, before that branch reaches
  `main`.)
- **The password.**
  - The first import sets the wallet password: at least eight characters,
    refused by the adapter otherwise. Every later import must give the same
    one, which is checked by unwrapping a stored envelope's DEK, never by a
    comparison. The account's own envelope is tried first, at most three
    are tried in all, and the seed stays sealed during the check. A GCM tag cannot tell a wrong password
    from a damaged file, so one envelope's refusal does not end the check
    while another may open. The password is wrong only when none opens and
    one refused it, and then nothing is sealed.
  - It is stretched from its NFKC form, so the same password typed on
    keyboards that compose characters differently opens the same envelopes.
- **Argon2id** is libsodium's `crypto_pwhash`, through a JNA mapping of its
  own (`Sodium`) over native buffers that are zeroed after the call. The
  bindings' String API would truncate a non-ASCII password and could not zero
  it. The mapping is checked against libsodium's known answers.
- **Costs.**
  - When every guess needs secure hardware (Android's Keystore HMAC): 64 MiB
    and three passes.
  - Otherwise 256 MiB and four passes. That covers a software key store, as
    on an emulator, whose key a copy of the device's storage yields. It
    covers the Mac's Secure Enclave, whose secret is unwrapped once and is
    then the same for every guess. And it covers the password alone. Each
    must hold up offline like a password-only envelope.
  - Opening checks the 64 MiB, two-pass floor and a 256 MiB, four-pass
    ceiling.
- **AES-256-GCM** comes from the JDK.
- **The hardware secret.** On Android it is `AndroidKeystoreSecret`, an
  HMAC-SHA256 key in StrongBox, or in the TEE when StrongBox is missing or
  failing. The Keystore reports where the key lives (rule 5), and the screen
  says which case applies: hardware, or a software key store on an emulator.
  A key store that fails is shown as such, and nothing can be added.
- **A Mac with a Secure Enclave** uses `MacSecureEnclaveSecret`, built as the
  iOS design above (owner's decision, 2026-10-16).
  - The key and the secret: `HW_pw` is a P-256 key made in the enclave, not
    permanent, with no user-authentication requirement. `S_hw` is 32 random
    bytes encrypted to it with ECIES.
  - The state file: `secure-enclave.json` beside the envelopes holds the key
    as the enclave's own encrypted handle, plus the encrypted `S_hw`.
    Neither is any use off this Mac.
  - The calls are Security.framework's C API through JNA, with no native
    build step. The handle is the key's token object id (`"toid"`), the same
    blob CryptoKit's `dataRepresentation` is, though the SDK does not name
    the attribute.
  - The handle carries no caller identity, so it is bound to the Mac but not
    to the app. Any program running as the same user can read the state file
    and use the enclave with it, with no permission prompt. Signing alone
    does not change that. Binding the key to the app means keeping it in the
    app's keychain access group, which takes a signed app with a
    provisioning profile, and is a different design.
  - The state file is made once, for a wallet with no envelope on the
    enclave yet, and never replaced. If it is lost while such envelopes
    exist, the key store reports itself unavailable instead of sealing under
    a new secret. A broken enclave or a damaged state shows as unavailable
    before any attempt, since describing the key store unwraps the secret
    once.
  - The tests run against the real enclave on a Mac and are skipped
    elsewhere.
- **The desktop's factor sticks to the wallet** (`desktopHardwareSecret`).
  - A wallet with enclave state or enclave envelopes keeps using the
    enclave. One that fails later makes the key store unavailable, never
    quietly password-only.
  - A wallet begun on the password alone stays so.
  - Only a wallet without envelopes asks whether the enclave works.
  - Macs without an enclave, Windows and Linux stand on the password alone.
    Rule 9's TPM path for Windows and Linux is not built. Until it is, those
    computers are password-only, and the seed screen says that Serotine does
    not use the computer's TPM yet (`KeyProtection.PASSWORD_ONLY_TPM_NOT_USED`,
    the owner's ruling of 2026-10-16).
- **One Serotine runs per user** on the desktop.
  - The host holds an exclusive lock on a file in its data directory, so a
    second instance does not write the account list or the envelopes. It
    says so and ends.
  - The OS drops the lock when the holder dies. A user who deletes the lock
    file while Serotine runs defeats the guard.
  - On Windows the data directory is `%LOCALAPPDATA%\eth.serotine`, apart
    from the directory the per-user installer owns.
- **A lost hardware key is said, not guessed around.** The key store asks the
  hardware secret whether its key exists before anything could create one.
  When envelopes sealed with that kind of secret exist but the key does not,
  the key store reports itself unavailable instead of making a new key, whose
  secret would only answer "wrong password"; signing with the password answers
  unavailable for the same reason, before the envelope is touched. Signing with
  a biometric does not need that key (its own key unwraps the DEK), so it still
  works. This covers a Keystore key lost in an OS upgrade or a cleared key
  store, and a deleted enclave state. The way back is the reset and recovery
  flows.
- **iOS** gets its envelope with the iOS app (owner's decision, 2026-10-16).
  Until then the key store reports itself unavailable there.
- **What never leaves a call.**
  - The seed, the keys and the password's bytes exist only inside one call,
    in byte arrays and native buffers this code zeroes before it returns.
  - The JDK's AES keeps its own copy of a key until the garbage collector
    takes it: the GC caveat of rule 7.
  - The words and the password reach the adapter along the mnemonic
    exception's one path. The restore and Create account screens'
    ViewModels drop both from their state when the screen stops being shown;
    an import already running finishes without them.
  - Create account's phrase is not stored until its check and password are
    done, so leaving the screen before then voids it: the screen tells the
    user to cross out what they wrote, and the next attempt gets a new
    phrase. A phrase the key adapter returns after the screen was left is
    dropped the same way.
- **Screen protections.** The restore and Create account screens carry rule
  8's protections through `protectFromScreenCapture()` in
  `eth.serotine.ui.platform`: `FLAG_SECURE` and an autofill exclusion on
  Android. On desktop, and on iOS until its host covers the window, the screen
  says that capture cannot be blocked. Create account also keeps the display
  on while its phrase is shown and checked (`KeepScreenOn()`; Android's
  `keepScreenOn`), because a display that times out is a screen left.
- **Not yet.**
  - Biometric unlock on a Mac's Secure Enclave (`.biometryCurrentSet`) and on
    iOS, and the opt-in on the seed screens at the first key (Settings →
    Security is where it is turned on today).
  - Changing the wallet password, which re-wraps every envelope's DEK as one
    step a crash cannot leave half done.
  - A way back when no envelope opens any more (a lost Keystore key, a
    forgotten password) comes with the wallet's reset and recovery flows.

## A Safe owned by a hardware P-256 key

Safe has supported secp256r1 owners since Safe 1.3.0 through its passkey
signer contracts (the `passkey` module of safe-modules): a signer proxy per
key, forwarding to a singleton that verifies a WebAuthn-formatted signature
over `authenticatorData || sha256(clientDataJSON)`. The singleton tries the
secp256r1 precompile at `0x100` first and falls back to a Solidity verifier
when the call returns empty, so one signer works on every chain Safe is
deployed on and gets cheap by itself when a chain gains the precompile.

Recommendations:

- **Use a raw Secure Enclave / StrongBox key, not a platform passkey**, for
  the hardware-bound tier. A raw key can sign an arbitrary digest
  (`.ecdsaSignatureDigestX962SHA256`; `NONEwithECDSA` on Android), so the app
  builds the WebAuthn envelope around it itself and the key stays
  device-bound instead of synced. Verify the constructed envelope against the
  signer contract's checks before shipping.
- **The owner key's own policy.** It is created with `.privateKeyUsage`, the
  passcode-set this-device-only accessibility class and, chosen by the user
  at creation, either `.biometryCurrentSet` alone (a biometric re-enrollment
  then invalidates it, which is what the recovery owner is for) or current
  biometrics or the device passcode. On Android the same two choices go
  through `setUserAuthenticationParameters` with per-use authorization and
  `BIOMETRIC_STRONG`, optionally with `DEVICE_CREDENTIAL`, plus
  `setUnlockedDeviceRequired(true)`. This tier has no password factor, so
  the envelope's biometric opt-in and its "disabling deletes the key" rule do
  not apply here: deleting this key removes the account's device owner, and
  the UI treats it as exactly that.
- **Expect gas to vary by chain.** With the precompile a verification costs
  thousands of gas; the fallback costs roughly 200k to 400k.
- **Such a key is never the sole owner of a Safe holding value.** See the
  next section.

### Where the secp256r1 precompile exists (checked October 2026)

| chain | precompile at `0x100` | since | via |
|---|---|---|---|
| Ethereum mainnet | yes | 2025-12-03 | Fusaka, EIP-7951 |
| Sepolia | yes | 2025-10-14 | Fusaka |
| Hoodi | yes | 2025-10-28 | Fusaka |
| Holesky | deprecated | | stopped finalizing 2025-10-27 |
| Gnosis Chain | yes | 2026-04-14 | Gnosis Fusaka |
| Chiado | assumed, not verified | | forks ahead of Gnosis mainnet as a rule |
| OP Mainnet, Base, other OP Stack chains | yes | 2024-07-10 | Fjord, RIP-7212; repriced to EIP-7951's 6900 gas in Azul |
| Arbitrum One and Nova | yes | 2024-09 | ArbOS 31 "Bianca" |
| Polygon PoS | yes | 2024-03 | Napoli |
| zkSync Era | yes | | `p256Verify` system contract at `0x100` |
| Scroll | yes | 2025-04 | Euclid |
| Linea | not verified | | |

EIP-7951 and RIP-7212 share the address and the calldata layout. EIP-7951
tightens input validation (a point-at-infinity check, modular comparison of
`r`) and doubles the gas to 6900; the L2s that started on RIP-7212 are
converging on it. A signer contract does not care which one it hits.

## Backup and recovery

A key generated inside the Secure Enclave or StrongBox never exists outside
the chip. Device backups, iCloud Keychain and Android device-to-device
transfer all skip it; a factory reset or wipe destroys it; a biometric
re-enrollment makes it unusable under the current-set flags above. **Hardware
isolation and backup are mutually exclusive on these platforms**: a hardware
key is a device credential, not a secret the user can own.

That is fine for a Safe, because a Safe needs owner rotation, not key backup:
losing the device is recovered by another owner calling `swapOwner` to
install the new device's key. So the account setup, not an extra step, is
one of:

- **Device key plus recovery key, threshold 1.** The hardware key signs daily.
  A secp256k1 owner from a mnemonic the user wrote down exists for recovery
  only and is, ideally, never stored on the phone at all.
- **Device key plus a recovery module.** A Safe recovery module lets a
  designated recoverer rotate owners after a delay, so the user keeps a veto
  window.
- **Two devices, threshold 1.** A second phone, the desktop app, or a
  hardware wallet as co-owner; either one rotates the other out.
- **Threshold 2 only with a third owner or a recovery module.** Two owners at
  threshold 2 is a brick the moment one device is lost: `swapOwner` is itself
  a Safe transaction that needs threshold signatures, the remaining owner can
  never reach two, and the lost device's key has no backup by design. So
  threshold 2 removes the single-device risk only as 2-of-3, or as 2-of-2
  with a recovery module alongside it.

For an **EOA** none of this exists: the mnemonic is the only backup, which is
the other reason an EOA key is a secp256k1 key from a mnemonic in the
envelope. The mnemonic is shown once at creation, written down by the user,
and re-entered only to restore on a new device.

Those two screens are an exception to the Trust rule that key material never
leaves the key-store adapter: the one entry, today, in the closed list of
exceptions in CLAUDE.md's *Trust* section (owner ruling, 2026-10-15). Its
conditions, which a reviewer holds those two flows to:

- **One type.** The words travel only as a dedicated mnemonic type whose
  `toString()` is redacted, so every place they flow is a place that type
  appears, and one search finds the whole path.
- **One path.** At creation, from the adapter through a use case and the
  ViewModel into a dedicated seed UiState; at restore, from the seed UiState
  through the ViewModel and a use case into the adapter. No other port, use
  case, ViewModel or screen sees the type.
- **Transient.** The seed UiState exists only while its screen is shown and
  the ViewModel clears it on leaving. Nothing on the path stores, logs,
  caches, snapshots or serializes the words; the input field's own text
  state is under the same limits as the password's in rule 7.
- **Protected.** Both screens are under rule 8's screen protections.
- **Narrow.** Everything the adapter derives from the words, the seed and the
  keys, stays under the absolute rule and never comes back up.

Every other screen and flow is held to the rule itself. A new exception is
the owner's ruling, added to that list first.

The **synced-passkey** tier trades hardware isolation for a backup held by
Apple or Google; it is listed above as the owner's open decision.

## What key storage does not do: signing intent

A hardware key gives three things: the key cannot be extracted, it cannot be
used without the device, and a human was present when it was used. It gives
nothing about *what* was signed. The Secure Enclave and StrongBox sign
whatever 32 bytes the app hands them; the biometric prompt proves the user
approved a signing operation, not this transaction with these contents. A
defective or compromised app can show one thing and sign another, and the
hardware cannot tell.

The only on-device fix would be a trusted display owned by the secure
hardware, which hardware wallets with screens have and phones do not:

- **Android Protected Confirmation** was built for exactly this. It shipped on
  Pixel phones only; Google moved to deprecate it in 2024 for lack of
  adoption, and the later partial revert does not make it something to build
  on. Its signed format would not match Safe's signer either.
- **iOS** exposes nothing comparable to third-party apps.

So the deception path is closed *outside* the signing app, in this order of
value:

1. **Policy on chain, where the app cannot touch it.** A Safe transaction
   guard or module enforcing spending limits, recipient allowlists, a delay
   for large or unusual transactions, no `delegatecall`, and owner changes
   only behind the delay. A defective app can then do only what the policy
   permits, and anything beyond it enters a window where another device can
   veto. This exists because a Safe is a contract; for an EOA the equivalent
   is an EIP-7702 delegation to such an account, and the delegation itself is
   still signed by the secp256k1 key.
2. **An independent second view.** A threshold-2 Safe whose second signer runs
   on a different device and decodes the transaction itself, never from the
   description the first device sent. The request reaches it as a QR code or
   as text with no off-chain service in between (owner ruling, 2026-10-16,
   under *Data sources* in CLAUDE.md): it carries the raw `SafeTx` fields,
   which the co-signer decodes, hashes and checks against the Safe's
   verified state. A hardware wallet co-signer does this with its own screen.
   Myotis can be that independent decoder for a desktop confirmation, as long
   as it never trusts the phone's description of the transaction.
3. **A smaller chance of the defect.** The signing path is one small,
   separately reviewed module with no feature flags or remote configuration
   on it. No dApp browser runs in the signing process; requests arrive
   through a connection protocol that runs outside it. WalletConnect is the
   candidate, and its relay is an off-chain service that goes under *Data
   sources* in CLAUDE.md before it is wired in; what arrives through it is
   untrusted input. Reproducible builds let others verify the binary. Calldata
   decoding and transaction simulation protect against malicious dApps, which
   is the common case, but not against the app itself.

For a Safe holding value, the app's own preview is therefore never the sole
check. Hardware keys close the theft path, Safe policies close most of the
deception path, and nothing on a phone closes it completely.

## Options considered and not taken, or deferred

- **External signers** (Ledger over Bluetooth, Keystone over QR, Keycard and
  Tangem over NFC) keep the secp256k1 key off the phone entirely and bring a
  trusted screen. For a Safe wallet this is the natural top tier, and a
  natural co-owner in the shapes above. Deferred: the owner decides when.
- **Two-party threshold ECDSA with Myotis**, where neither the device share
  nor the server share can sign alone. Real protocol complexity, an
  availability dependency, and the device share still needs the envelope.
  Deferred; the owner's decision.
- **Deriving the secp256k1 key on demand from a hardware key** (an HMAC key on
  Android, a fixed-peer ECDH on iOS) so that nothing is stored. The plaintext
  still exists in the app at signing time, the password cannot be bound in,
  and the key becomes unrecoverable with the device while a mnemonic backup
  is needed anyway. No gain over the envelope; rejected.
- **Samsung Blockchain Keystore.** Galaxy-only; rejected.

## Sources

- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore?hl=en)
- [Android: backup leaks, allowBackup and device-to-device transfer](https://developer.android.com/topic/security/risks/backup-leaks)
- [Apple CryptoKit SecureEnclave](https://developer.apple.com/documentation/cryptokit/secureenclave.md)
- [Apple SecAccessControl.h, the application-password flag](https://opensource.apple.com/source/Security/Security-58286.51.6/keychain/SecAccessControl.h.auto.html)
- [Apple developer forum: application password with a Secure Enclave key](https://developer.apple.com/forums/thread/118746)
- [Samsung Blockchain Keystore SDK](https://developer.samsung.com/blockchain/keystore)
- [Safe passkeys](https://docs.safe.global/home/passkeys-safe)
- [RIP-7212](https://github.com/ethereum/RIPs/blob/master/RIPS/rip-7212.md) and [EIP-7951](https://eips.ethereum.org/EIPS/eip-7951)
- [Fusaka mainnet announcement](https://blog.ethereum.org/2025/11/06/fusaka-mainnet-announcement)
- [Fusaka testnet dates](https://status.quicknode.com/incidents/05cv4z7mkqns) and [Holesky deprecation, ACDT #59](https://ethereum-magicians.org/t/all-core-devs-testing-acdt-59-oct-27-2025/25914)
- [Gnosis Chain Fusaka upgrade](https://status.quicknode.com/incidents/qzc59nbc51rb)
- [Base Fjord](https://docs.base.org/upgrades/fjord/overview.md) and [Base precompiles, Azul repricing](https://docs.base.org/specifications/base-protocol/execution/precompiles.md)
- [OP Stack Fjord](https://docs.optimism.io/op-stack/protocol/hardforks/fjord.md)
- [ArbOS 31 Bianca](https://docs.arbitrum.io/run-arbitrum-node/arbos-releases/arbos31)
- [zkSync Era precompiles](https://docs.zksync.io/zksync-protocol/differences/pre-compiles)
- [Scroll Euclid](https://www.theblock.co/post/351601/ethereum-layer-2-scroll-euclid-upgrade-improves-network-throughput-security)
- [Android Protected Confirmation: deprecation](https://gerrit.omnirom.org/plugins/gitiles/android_frameworks_base/+/3d9d17788fb713787bda740a335304d1b346e19b) and [its partial revert](https://gerrit.omnirom.org/plugins/gitiles/android_system_security/+/9535b1b44373844b239201b18c535829d6d52eba%5E%21)
