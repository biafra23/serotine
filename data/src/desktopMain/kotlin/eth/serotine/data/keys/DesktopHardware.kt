package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The hardware secret for the desktop key store whose envelopes live in [keysDirectory]
 * (docs/keys-and-signing.md, rule 9): this Mac's Secure Enclave ([MacSecureEnclaveSecret])
 * where the wallet uses it or can start to, otherwise none, the password alone.
 *
 * The choice sticks to the wallet, so it never ends up under two factors. Once the enclave
 * holds the wallet's secret, it is used again; an enclave that fails later makes the key
 * store unavailable rather than quietly password-only. A wallet begun on the password alone
 * stays so. Only a wallet without envelopes asks whether the enclave works.
 */
fun desktopHardwareSecret(keysDirectory: String): HardwareSecret? {
    if (!MacSecurity.onMac) return null
    return try {
        val stateFile = Path(keysDirectory, MacSecureEnclaveSecret.STATE_FILE_NAME)
        val factor = chooseDesktopFactor(
            enclaveStateExists = SystemFileSystem.exists(stateFile),
            envelopeTags = envelopeTagsIn(keysDirectory),
            enclaveUsable = MacSecureEnclaveSecret::usable,
        )
        if (factor == DesktopFactor.SECURE_ENCLAVE) MacSecureEnclaveSecret(stateFile.toString()) else null
    } catch (e: Throwable) {
        // Not knowing the wallet's factor is no reason to guess one: the key store reports
        // itself unavailable, and the window still opens. Said here, where the cause is known.
        val reason = "the desktop's hardware factor cannot be determined (${e.message ?: e::class.simpleName})"
        println("Serotine: $reason")
        UnavailableHardware(reason)
    }
}

/**
 * What the desktop key store reports when [desktopHardwareSecret] gives it no secret: on a Mac,
 * that Serotine uses no hardware key store there; on Windows and Linux, that it does not use the
 * computer's TPM yet, since rule 9's TPM path is not built (the owner's ruling of 2026-10-16).
 */
fun desktopPasswordOnlyProtection(): KeyProtection = passwordOnlyProtection(onMac = MacSecurity.onMac)

internal fun passwordOnlyProtection(onMac: Boolean): KeyProtection =
    if (onMac) KeyProtection.PASSWORD_ONLY else KeyProtection.PASSWORD_ONLY_TPM_NOT_USED

internal enum class DesktopFactor { SECURE_ENCLAVE, PASSWORD_ONLY }

/** [desktopHardwareSecret]'s rule, on its inputs: [enclaveUsable] is asked only for a wallet without envelopes. */
internal fun chooseDesktopFactor(
    enclaveStateExists: Boolean,
    envelopeTags: Set<String>,
    enclaveUsable: () -> Boolean,
): DesktopFactor = when {
    enclaveStateExists || MacSecureEnclaveSecret.TAG in envelopeTags -> DesktopFactor.SECURE_ENCLAVE
    envelopeTags.isNotEmpty() -> DesktopFactor.PASSWORD_ONLY
    enclaveUsable() -> DesktopFactor.SECURE_ENCLAVE
    else -> DesktopFactor.PASSWORD_ONLY
}
