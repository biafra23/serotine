package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection

/** A [HardwareSecret] for tests: a keyed, deterministic mix, and the protection it claims. */
internal class FakeHardware(
    override val tag: String = "fake-hw-v1",
    private val key: Byte = 42,
    private val claims: () -> KeyProtection = { KeyProtection.HARDWARE },
    override val everyGuessNeedsDevice: Boolean = true,
    private val provisioned: () -> Boolean = { true },
    private val mixed: () -> Unit = {},
) : HardwareSecret {
    override fun isProvisioned(): Boolean = provisioned()

    override val protection: KeyProtection
        get() = claims()

    override fun mix(input: ByteArray): ByteArray {
        mixed()
        return ByteArray(32) { (input[it % input.size].toInt() xor key.toInt()).toByte() }
    }
}
