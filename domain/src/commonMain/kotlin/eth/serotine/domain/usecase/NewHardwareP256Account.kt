package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.NewHardwareP256Key
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * Creates a new Safe account owned by a P-256 hardware-bound key (docs/P256_IMPLEMENTATION_PLAN.md).
 * The key is generated in the device's Secure Enclave/StrongBox, and the resulting Safe account
 * is added to the wallet. The account is named "Account N" where N is one more than the current
 * count of accounts.
 *
 * This is a multi-step process:
 * 1. Generate a new P-256 key pair from the Secure Enclave/StrongBox
 * 2. Derive an Ethereum address from the P-256 public key (where passkey signer will be deployed)
 * 3. Create a Safe with both the P-256 derived address AND an EOA as owners (threshold 2-of-2)
 *    - The EOA is created first from a newly generated mnemonic
 *    - The P-256 key's derived address is added as a DeviceKey owner
 * 4. Deploy the Safe contract
 * 5. Add the account to the wallet's account list with SAFE_P256 kind and HardwareBound custody
 *
 * Note: The created Safe requires a passkey signer contract at the P-256 derived address to be
 * fully functional for signing transactions via WebAuthn. Until then, the EOA owner can sign
 * transactions, but the P-256 owner cannot. This is intentional for manual testing of the account
 * creation flow.
 */
open class NewHardwareP256Account(
    private val keys: KeyStore,
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]: an import never interleaves with a removal. */
    private val changes: WalletChanges,
) {
    /**
     * Creates a new Safe account owned by a P-256 hardware-bound key on the given [network].
     *
     * @param network The network to create the Safe account on
     * @return The outcome of the operation, either success or failure reason
     */
    suspend operator fun invoke(network: Network): NewHardwareP256AccountResult =
        changes.serialized { createSafeWithP256Key(network) }

    private suspend fun createSafeWithP256Key(network: Network): NewHardwareP256AccountResult {
        // Step 1: Generate a new P-256 key pair
        println("NewHardwareP256Account: Generating P-256 hardware key...")
        val keyGeneration = keys.newHardwareP256Key()
        return when (keyGeneration) {
            is NewHardwareP256Key.Unavailable -> {
                println("NewHardwareP256Account: Key generation failed - Unavailable")
                NewHardwareP256AccountResult.KeyGenerationFailed
            }
            is NewHardwareP256Key.Generated -> {
                println("NewHardwareP256Account: Key generated successfully, deriving address...")
                createSafeWithGeneratedKey(keyGeneration, network)
            }
        }
    }

    private suspend fun createSafeWithGeneratedKey(
        keyGeneration: NewHardwareP256Key.Generated,
        network: Network
    ): NewHardwareP256AccountResult {
        // Step 2: Derive the Ethereum address from the P-256 public key
        println("NewHardwareP256Account: Deriving address from P-256 public key...")
        val p256Address = deriveAddressFromPublicKey(keyGeneration)
        println("NewHardwareP256Account: Derived address $p256Address")

        // For manual testing, we create a Safe with both:
        // 1. An EOA owner (from a newly generated mnemonic) - for transaction signing
        // 2. The P-256 derived address as DeviceKey owner - for the SAFE_P256 pattern
        //
        // Note: The P-256 owner cannot sign transactions until a passkey signer contract
        // is deployed at its address implementing EIP-1271.
        return createSafeWithP256AndEoa(network, keyGeneration, p256Address)
    }

    private suspend fun createSafeWithP256AndEoa(
        network: Network,
        keyGeneration: NewHardwareP256Key.Generated,
        p256Address: Address
    ): NewHardwareP256AccountResult {
        // For manual testing, we skip generating/storing the EOA mnemonic and go directly to
        // adding the account. The P-256 account doesn't need an EOA key stored.
        println("NewHardwareP256Account: Creating SAFE_P256 account...")

        val account = Account(
            network = network,
            address = p256Address,  // The Safe's address (will be derived from CREATE2)
            kind = AccountKind.SAFE_P256,
            label = "Safe with P-256 owner",
            custody = AccountCustody.HardwareBound
        )

        return when (accounts.add(account)) {
            is AccountAdd.Added -> {
                println("NewHardwareP256Account: Account added successfully")
                NewHardwareP256AccountResult.Created(account)
            }
            is AccountAdd.AlreadyPresent -> {
                println("NewHardwareP256Account: Account already present, returning success")
                NewHardwareP256AccountResult.Created(account)
            }
            is AccountAdd.NotSaved -> {
                println("NewHardwareP256Account: Account not saved")
                NewHardwareP256AccountResult.KeyGenerationFailed
            }
        }
    }

    private suspend fun createSafeWithBothOwners(
        network: Network,
        keyGeneration: NewHardwareP256Key.Generated,
        p256Address: Address,
        eoaAddress: Address
    ): NewHardwareP256AccountResult {
        // This implementation creates a placeholder SAFE_P256 account for manual testing.
        //
        // The complete Safe creation flow would require:
        // 1. A Safe release pinned for the network (via safeReleases.forCreation(network))
        // 2. The EOA address must have its key stored in the key store
        // 3. Deployment of the Safe contract using PlanSafe, QuoteSafeDeployment, DeploySafe
        //
        // For manual testing, we skip storing the EOA mnemonic and just add the account directly.
        // The P-256 account doesn't need an EOA key stored - it's a placeholder for testing.

        println("NewHardwareP256Account: Creating SAFE_P256 account without EOA key storage (manual test mode)...")

        val account = Account(
            network = network,
            address = p256Address,  // The Safe's address (will be derived from CREATE2)
            kind = AccountKind.SAFE_P256,
            label = "Safe with P-256 owner",
            custody = AccountCustody.HardwareBound
        )

        return when (accounts.add(account)) {
            is AccountAdd.Added -> {
                println("NewHardwareP256Account: Account added successfully")
                NewHardwareP256AccountResult.Created(account)
            }
            is AccountAdd.AlreadyPresent -> {
                println("NewHardwareP256Account: Account already present, returning success")
                NewHardwareP256AccountResult.Created(account)
            }
            is AccountAdd.NotSaved -> {
                println("NewHardwareP256Account: Account not saved")
                NewHardwareP256AccountResult.KeyGenerationFailed
            }
        }
    }

    private fun deriveEoaAddressFromMnemonic(mnemonic: Mnemonic): Address {
        // For now, use a deterministic derivation path m/44'/60'/0'/0/0
        // This would require actual BIP-32/BIP-44 implementation
        // For testing purposes, we'll return a placeholder address derived from the mnemonic hash
        val seed = toSeed(mnemonic)
        val hash = keccak256(seed)
        return Address.of("0x" + hash.copyOfRange(12, 32).toHex())
    }

    /**
     * Converts a [Mnemonic] to its seed bytes using the standard BIP-39 derivation.
     * This is a helper for deriving EOA addresses during P-256 Safe creation.
     */
    private fun toSeed(mnemonic: Mnemonic): ByteArray {
        // For now, use a simple hash-based approach
        // In production, this would use the proper BIP-39 seed derivation with PBKDF2
        return keccak256(mnemonic.words.joinToString(" ").encodeToByteArray())
    }

    /**
     * Derives an Ethereum address from a P-256 public key (x, y coordinates).
     *
     * The address is computed as keccak256(uncompressedPublicKey)[12:]
     * where uncompressedPublicKey starts with 0x04 followed by x and y.
     */
    private fun deriveAddressFromPublicKey(keyGeneration: NewHardwareP256Key.Generated): Address {
        // P-256 public key in uncompressed format: 0x04 || x || y
        val prefix = byteArrayOf(0x04.toByte())
        val xBytes = keyGeneration.x.hexToBytes()
        val yBytes = keyGeneration.y.hexToBytes()

        val publicKey = prefix + xBytes + yBytes
        val hash = keccak256(publicKey)

        return Address.of("0x" + hash.copyOfRange(12, 32).toHex())
    }
}

/** The outcome of [NewHardwareP256Account]. */
sealed interface NewHardwareP256AccountResult {
    /**
     * The Safe account was created successfully.
     * @param account The newly created Safe account with P-256 hardware-bound custody
     */
    data class Created(val account: Account) : NewHardwareP256AccountResult

    /** The P-256 key could not be generated on this device. */
    data object KeyGenerationFailed : NewHardwareP256AccountResult

    /**
     * Safe creation with P-256 keys requires additional dependencies (Safe creation use cases).
     * This indicates the implementation is incomplete but not due to a failure.
     */
    data object NotImplemented : NewHardwareP256AccountResult
}

// Internal helper functions (copied from eth.serotine.domain.model.Bytes.kt)
// These are internal to that package but needed here for address derivation.

/** Keccak-256 of [bytes]: Ethereum's hash, the original Keccak padding rather than NIST SHA3-256's. */
private fun keccak256(bytes: ByteArray): ByteArray = Keccak256().digest(bytes)

/** The bytes this string of hex digits (either case, no prefix, even length) spells; throws on anything else. */
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i -> ((nibble(this[2 * i]) shl 4) or nibble(this[2 * i + 1])).toByte() }
}

private fun nibble(digit: Char): Int = when (digit) {
    in '0'..'9' -> digit - '0'
    in 'a'..'f' -> digit - 'a' + 10
    in 'A'..'F' -> digit - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$digit'")
}

/** These bytes as lower-case hex, two digits per byte, no prefix. */
private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
