package eth.serotine.data.safe

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network

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
 */
internal object SafeDeployments {

    /** The proxy code hashes and singletons of one network. */
    class Table(val proxyCodeHashes: Set<String>, val singletons: Map<Address, SafeSingleton>)

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
    )

    private fun singleton(address: String, version: String, l2: Boolean, moduleGuard: Boolean = false) =
        Address.of(address) to SafeSingleton(version, l2, moduleGuard)
}
