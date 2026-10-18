package eth.serotine.data.safe

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeRelease

/**
 * A canonical Safe singleton: its [version] ("1.4.1"), whether it is the L2 variant ([l2]),
 * and whether it consults a module guard ([moduleGuard]: 1.5.0 on; older ones never read it).
 */
internal data class SafeSingleton(val version: String, val l2: Boolean, val moduleGuard: Boolean = false)

/**
 * The Safe deployments a Safe is recognised by, per network (docs/wallet-ux.md, *Safes*): the
 * runtime code hashes of the canonical SafeProxy contracts ([Table.proxyCodeHashes], lower-case
 * hex without `0x`), and the canonical singletons a proxy's slot 0 must name
 * ([Table.singletons]). A contract matching neither is "another contract", never a Safe.
 *
 * Read on Sepolia on 2026-10-17 with verified reads through the Myotis engine, at blocks
 * 11,933,239 to 11,933,240: each singleton's code, and its `VERSION()` as a verified call;
 * each proxy hash as the code a verified create call of the factory's `proxyCreationCode()`
 * returns (1.3.0's also from its `proxyRuntimeCode()`, the same). The eip155 deployments of
 * 1.3.0 have the same code as the canonical ones.
 *
 * A new Safe is created from [Table.creation] (docs/wallet-ux.md, *Safes*: creation): Safe 1.4.1,
 * its L1 singleton (Safe{Wallet}'s choice for Ethereum and its testnets) and its SafeProxyFactory,
 * with no fallback handler: `setup` installs none. (The one CompatibilityFallbackHandler a Safe
 * transaction may set is pinned in the domain, `PinnedFallbackHandlers`, beside the check that
 * refuses any other.) The factory's proxy creation code is `SafeProxy`'s `bytecode` in the npm package
 * `@safe-global/safe-contracts` 1.4.1-build.0
 * (`build/artifacts/contracts/proxies/SafeProxy.sol/SafeProxy.json`), the build the canonical
 * deployment came from: the runtime code it deploys hashes to the 1.4.1 proxy hash below, read on
 * Sepolia, and that package's SafeProxyFactory runtime code hashes to the factory's code hash in
 * safe-global/safe-deployments (`src/assets/v1.4.1/safe_proxy_factory.json`, `0x50c3cdc4…`). It
 * is only what is expected: a deployment is quoted only when the factory's verified run returns
 * the address computed from it.
 */
internal object SafeDeployments {

    /**
     * The proxy code hashes and singletons of one network, and the release a new Safe is created
     * from there ([creation], null: none).
     */
    class Table(
        val proxyCodeHashes: Set<String>,
        val singletons: Map<Address, SafeSingleton>,
        val creation: SafeRelease?,
    )

    /** The deployments of [network]. */
    fun of(network: Network): Table = when (network) {
        Network.SEPOLIA -> SEPOLIA
    }

    private val SEPOLIA = Table(
        proxyCodeHashes = setOf(
            // 1.3.0, from GnosisSafeProxyFactory 0xa6B71E26C5e0845f74c812102Ca7114b6a896AB2.
            "b89c1b3bdf2cf8827818646bce9a8f6e372885f8c55e5c07acbd307cb133b000",
            // 1.4.1, from SafeProxyFactory 0x4e1DCf7AD4e460CfD30791CCC4F9c8a4f820ec67.
            "d7d408ebcd99b2b70be43e20253d6d92a8ea8fab29bd3be7f55b10032331fb4c",
            // 1.5.0, from SafeProxyFactory 0x14F2982D601c9458F93bd70B218933A6f8165e7b.
            "4e381985ca68b3e5d27b4425fa581c19cf33146d3f887a3cfca96f55528ea46f",
        ),
        singletons = mapOf(
            singleton("0xd9db270c1b5e3bd161e8c8503c55ceabee709552", "1.3.0", l2 = false),
            singleton("0x3e5c63644e683549055b9be8653de26e0b4cd36e", "1.3.0", l2 = true),
            singleton("0x69f4d1788e39c87893c980c06edf4b7f686e2938", "1.3.0", l2 = false),
            singleton("0xfb1bffc9d739b8d520daf37df666da4c687191ea", "1.3.0", l2 = true),
            singleton("0x41675c099f32341bf84bfc5382af534df5c7461a", "1.4.1", l2 = false),
            singleton("0x29fcb43b46531bca003ddc8fcb67ffe91900c762", "1.4.1", l2 = true),
            singleton("0xff51a5898e281db6dfc7855790607438df2ca44b", "1.5.0", l2 = false, moduleGuard = true),
            singleton("0xedd160febbd92e350d4d398fb636302fccd67c7e", "1.5.0", l2 = true, moduleGuard = true),
        ),
        creation = SafeRelease(
            version = "1.4.1",
            l2 = false,
            singleton = Address.of("0x41675c099f32341bf84bfc5382af534df5c7461a"),
            proxyFactory = Address.of("0x4e1dcf7ad4e460cfd30791ccc4f9c8a4f820ec67"),
            proxyCreationCode = PROXY_CREATION_CODE_1_4_1.fromHex(),
            fallbackHandler = Address.ZERO,
        ),
    )

    private fun singleton(address: String, version: String, l2: Boolean, moduleGuard: Boolean = false) =
        Address.of(address) to SafeSingleton(version, l2, moduleGuard)
}

/**
 * `type(SafeProxy).creationCode` of Safe 1.4.1's SafeProxyFactory: 486 bytes, the constructor and
 * then, at offset 0x119, the 171 bytes of runtime code it deploys (see [SafeDeployments]).
 */
private const val PROXY_CREATION_CODE_1_4_1 =
    "608060405234801561001057600080fd5b506040516101e63803806101e6833981810160405260208110156100335760" +
    "0080fd5b8101908080519060200190929190505050600073ffffffffffffffffffffffffffffffffffffffff168173ff" +
    "ffffffffffffffffffffffffffffffffffffff1614156100ca576040517f08c379a00000000000000000000000000000" +
    "000000000000000000000000000081526004018080602001828103825260228152602001806101c46022913960400191" +
    "505060405180910390fd5b806000806101000a81548173ffffffffffffffffffffffffffffffffffffffff0219169083" +
    "73ffffffffffffffffffffffffffffffffffffffff1602179055505060ab806101196000396000f3fe608060405273ff" +
    "ffffffffffffffffffffffffffffffffffffff600054167fa619486e0000000000000000000000000000000000000000" +
    "000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e600081" +
    "14156070573d6000fd5b3d6000f3fea264697066735822122003d1488ee65e08fa41e58e888a9865554c535f2c77126a" +
    "82cb4c0f917f31441364736f6c63430007060033496e76616c69642073696e676c65746f6e2061646472657373207072" +
    "6f7669646564"
