package eth.serotine.data.safe

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Network
import org.kotlincrypto.hash.sha3.Keccak256
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pins against the real SafeProxy runtime code, so a typo'd hash cannot pass for one. */
class SafeDeploymentsTest {

    /**
     * Each factory's proxy runtime code, as a verified create call of its `proxyCreationCode()`
     * returned it through the Myotis engine on Sepolia, 2026-10-17 (1.3.0's equal to its own
     * `proxyRuntimeCode()`).
     */
    private val runtimeCode = mapOf(
        "1.3.0" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea2646970667358221220d1429297349653a4918076d650332de1a1068c5f3e07" +
            "c5c82360c277770b955264736f6c63430007060033",
        "1.4.1" to
            "608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e000000000000000000000000000000" +
            "0000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e" +
            "60008114156070573d6000fd5b3d6000f3fea264697066735822122003d1488ee65e08fa41e58e888a9865554c535f2c7712" +
            "6a82cb4c0f917f31441364736f6c63430007060033",
        "1.5.0" to
            "608060405260005463a619486e60003560e01c14156024578060601b606c5260206060f35b3660008037600080366000845a" +
            "f43d6000803e806040573d6000fd5b3d6000f3fea2646970667358221220e61834ebd2d8cd909d362bf67c47ef58fd665df3" +
            "8e6dd036ce65611101d072e964736f6c63430007060033",
    )

    @Test
    fun everyPinnedProxyHashIsTheHashOfASafeProxysRuntimeCode() {
        val hashes = runtimeCode.values.map { Keccak256().digest(it.fromHex()).toHex() }.toSet()

        assertEquals(SafeDeployments.of(Network.SEPOLIA).proxyCodeHashes, hashes)
    }

    @Test
    fun onlyFromVersionOneFiveAModuleGuardIsConsulted() {
        val singletons = SafeDeployments.of(Network.SEPOLIA).singletons.values

        assertEquals(setOf("1.5.0"), singletons.filter { it.moduleGuard }.map { it.version }.toSet())
        assertEquals(setOf("1.3.0", "1.4.1", "1.5.0"), singletons.map { it.version }.toSet())
    }
}
