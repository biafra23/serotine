package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * The real engine on the real Sepolia: boots a [MyotisNode] and waits for a verified
 * balance. Needs the network and the engine's host library, so it is skipped unless run
 * with `-PmyotisLibraryDir=<dir with libmyotis_engine>` (data/build.gradle.kts passes it
 * on as java.library.path), e.g.
 * `./gradlew :data:desktopTest --tests '*MyotisNodeSepoliaTest*' -PmyotisLibraryDir=…`.
 */
class MyotisNodeSepoliaTest {

    @Test
    fun aSyncedNodeReadsAVerifiedSepoliaBalance(): Unit = runBlocking {
        assumeTrue("needs -PmyotisLibraryDir", System.getProperty("serotine.myotisLibraryDir") != null)
        // A fresh directory: a cold start from the embedded checkpoint, every phase shown.
        val node = MyotisNode.start(Network.SEPOLIA, Files.createTempDirectory("myotis-sepolia").toFile())
        val started = System.nanoTime()
        val progress = launch {
            node.lightClient.observeStatus().collect {
                println("light client after ${(System.nanoTime() - started) / 1_000_000_000}s: $it")
            }
        }
        try {
            // The Sepolia deposit contract: a balance that exists on every Sepolia state.
            val address = Address.of("0x7f02c3e3c98b133055b8b348b2ac625669ed295d")
            val reading = withTimeout(10.minutes) {
                // Past "not verified" (a proof no peer has served yet); a refusal ends it at once.
                node.nativeBalances.observeBalance(address).first {
                    it is BalanceReading.Verified ||
                        (it is BalanceReading.Unavailable && it.reason != ReadUnavailable.UNPROVEN)
                }
            }
            println("Sepolia balance of $address: $reading")
            assertIs<BalanceReading.Verified>(reading)
        } finally {
            progress.cancel()
            node.shutdown()
        }
    }
}
