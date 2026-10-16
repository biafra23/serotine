package eth.serotine.data.keys

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json

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
    val stateFile = Path(keysDirectory, MacSecureEnclaveSecret.STATE_FILE_NAME)
    val factor = chooseDesktopFactor(
        enclaveStateExists = SystemFileSystem.exists(stateFile),
        envelopeTags = envelopeTags(keysDirectory),
        enclaveUsable = MacSecureEnclaveSecret::usable,
    )
    return if (factor == DesktopFactor.SECURE_ENCLAVE) MacSecureEnclaveSecret(stateFile.toString()) else null
}

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

/** The hardware tags of the envelopes in [directory] that can be read; the others say nothing. */
private fun envelopeTags(directory: String): Set<String> {
    val dir = Path(directory)
    if (!SystemFileSystem.exists(dir)) return emptySet()
    return SystemFileSystem.list(dir).filter { it.name.endsWith(".seed") }.mapNotNullTo(HashSet()) { file ->
        try {
            SystemFileSystem.source(file).buffered().use { source ->
                Json.decodeFromString(SealedSeed.serializer(), source.readString()).hardware
            }
        } catch (e: Exception) {
            null
        }
    }
}
