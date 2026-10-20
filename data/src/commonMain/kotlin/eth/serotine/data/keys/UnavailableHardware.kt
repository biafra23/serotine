package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection

/**
 * A hardware factor that cannot be had, for [reason]: every use fails, so the key store reports
 * itself unavailable instead of guessing the wallet's factor (the desktop's and iOS's choice of
 * factor, when it cannot be made).
 */
internal class UnavailableHardware(private val reason: String) : HardwareSecret {
    override val tag: String = "unavailable"
    override val protection: KeyProtection get() = error(reason)
    override val everyGuessNeedsDevice: Boolean = false
    override fun isProvisioned(): Boolean = error(reason)
    override fun mix(input: ByteArray): ByteArray = error(reason)
}
