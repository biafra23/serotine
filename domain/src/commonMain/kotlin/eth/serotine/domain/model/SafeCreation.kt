package eth.serotine.domain.model

import eth.serotine.domain.model.AbiWords.WORD
import eth.serotine.domain.model.AbiWords.dynamic
import eth.serotine.domain.model.AbiWords.selector
import eth.serotine.domain.model.AbiWords.word

/**
 * A Safe release this wallet creates Safes from (docs/wallet-ux.md, *Safes*: creation): its
 * [version] ("1.4.1"), whether its singleton is the L2 variant ([l2]), the [singleton] a new
 * proxy delegates every call to, the [proxyFactory] whose `createProxyWithNonce` deploys the
 * proxy, the factory's [proxyCreationCode] (`type(SafeProxy).creationCode`, which the CREATE2
 * address is computed over), and the [fallbackHandler] `setup` installs (the zero address: none).
 *
 * Pinned per network by :data. The address of a new Safe is predicted from these alone
 * ([SafeCreation.proxyAddress]); the factory's own verified run must return the same address
 * before anything is signed, so a pin that does not match the chain refuses the deployment
 * rather than send it somewhere unforeseen. Holds a copy of the code and hands out copies.
 */
class SafeRelease(
    val version: String,
    val l2: Boolean,
    val singleton: Address,
    val proxyFactory: Address,
    proxyCreationCode: ByteArray,
    val fallbackHandler: Address,
) {
    private val code = proxyCreationCode.copyOf()

    /** `type(SafeProxy).creationCode` of [proxyFactory]: a copy. */
    val proxyCreationCode: ByteArray
        get() = code.copyOf()

    override fun equals(other: Any?): Boolean = other is SafeRelease &&
        version == other.version &&
        l2 == other.l2 &&
        singleton == other.singleton &&
        proxyFactory == other.proxyFactory &&
        code.contentEquals(other.code) &&
        fallbackHandler == other.fallbackHandler

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + l2.hashCode()
        result = 31 * result + singleton.hashCode()
        result = 31 * result + proxyFactory.hashCode()
        result = 31 * result + code.contentHashCode()
        return 31 * result + fallbackHandler.hashCode()
    }

    override fun toString(): String =
        "SafeRelease($version${if (l2) " L2" else ""}, singleton=$singleton, factory=$proxyFactory)"
}

/**
 * The calls that create a Safe, and the address it gets, as safe-smart-account 1.4.1 defines
 * them (`Safe.setup`, `SafeProxyFactory.createProxyWithNonce` and its `deployProxy`). Pure: the
 * address is computed here, before anything is sent, and checked against the factory's own
 * verified run ([eth.serotine.domain.usecase.QuoteSafeDeployment]).
 */
object SafeCreation {
    private val SETUP = selector("setup(address[],uint256,address,bytes,address,address,uint256,address)")
    private val CREATE_PROXY_WITH_NONCE = selector("createProxyWithNonce(address,bytes,uint256)")

    /** The eight head words of `setup`: where its two dynamic arguments start. */
    private const val SETUP_HEAD_WORDS = 8

    /** The three head words of `createProxyWithNonce`. */
    private const val CREATE_HEAD_WORDS = 3

    /** The byte CREATE2 hashes before the deployer (EIP-1014). */
    private const val CREATE2_PREFIX: Byte = 0xff.toByte()

    /**
     * The initializer of a new Safe: `setup(owners, threshold, to, data, fallbackHandler,
     * paymentToken, payment, paymentReceiver)` with [owners] in their order, [threshold], no setup
     * call (`to` the zero address, `data` empty: no module is enabled), [fallbackHandler], and no
     * payment (token, amount and receiver all zero). The proxy runs it in the same transaction that
     * deploys it, so nobody can set a Safe up in between.
     */
    fun setup(owners: List<Address>, threshold: Int, fallbackHandler: Address): ByteArray {
        require(threshold >= 0) { "a threshold is not negative" }
        val ownersTail = word(owners.size.toLong()) + owners.fold(ByteArray(0)) { tail, it -> tail + word(it) }
        val ownersOffset = SETUP_HEAD_WORDS * WORD
        val dataOffset = ownersOffset + ownersTail.size
        return SETUP +
            word(ownersOffset.toLong()) +
            word(threshold.toLong()) +
            word(Address.ZERO) +
            word(dataOffset.toLong()) +
            word(fallbackHandler) +
            word(Address.ZERO) +
            word(0) +
            word(Address.ZERO) +
            ownersTail +
            dynamic(ByteArray(0))
    }

    /**
     * The call data of `createProxyWithNonce(singleton, initializer, saltNonce)`: what the factory
     * is sent to deploy a proxy of [singleton] at [proxyAddress] and run [initializer] on it.
     */
    fun createProxyWithNonce(singleton: Address, initializer: ByteArray, saltNonce: UInt256): ByteArray =
        CREATE_PROXY_WITH_NONCE +
            word(singleton) +
            word((CREATE_HEAD_WORDS * WORD).toLong()) +
            saltNonce.toBytes32() +
            dynamic(initializer)

    /**
     * Where [release]'s factory deploys the proxy that `createProxyWithNonce(release.singleton,
     * initializer, saltNonce)` makes: CREATE2 (EIP-1014) from the factory, with the salt
     * `keccak256(keccak256(initializer) ‖ saltNonce)` and the init code the proxy's creation code
     * followed by the singleton as a uint256 — `keccak256(0xff ‖ factory ‖ salt ‖
     * keccak256(initCode))[12:]`.
     */
    fun proxyAddress(release: SafeRelease, initializer: ByteArray, saltNonce: UInt256): Address {
        val salt = keccak256(keccak256(initializer) + saltNonce.toBytes32())
        val initCodeHash = keccak256(release.proxyCreationCode + word(release.singleton))
        return create2Address(release.proxyFactory, salt, initCodeHash)
    }

    /**
     * The address CREATE2 gives the contract [deployer] creates with [salt] from the init code that
     * hashes to [initCodeHash].
     */
    fun create2Address(deployer: Address, salt: ByteArray, initCodeHash: ByteArray): Address {
        require(salt.size == WORD && initCodeHash.size == WORD) { "a salt and a code hash are 32 bytes each" }
        val hash = keccak256(byteArrayOf(CREATE2_PREFIX) + deployer.toBytes() + salt + initCodeHash)
        return Address.of("0x" + hash.copyOfRange(WORD - ADDRESS_BYTES, WORD).toHex())
    }

    /**
     * The address a call returned as its one ABI word ([returnHex], lower-case hex without `0x`):
     * what `createProxyWithNonce` returns. Null when it returned anything else.
     */
    fun returnedAddress(returnHex: String): Address? = AbiWords.returnedWord(returnHex)?.let(AbiWords::address)
}

/** The size of an address. */
private const val ADDRESS_BYTES = 20
