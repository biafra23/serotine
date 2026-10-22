package eth.serotine.data.cow

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowQuoteReading
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CoW Protocol's real order API on Sepolia, through the platform's own engine (CIO on the desktop,
 * TLS included): a quote decoded and checked, and an order it does not know. It needs the network,
 * so it is skipped unless run with `-PcowApiNetwork` (data/build.gradle.kts), e.g.
 * `./gradlew :data:desktopTest --tests '*CowApiSepoliaTest*' -PcowApiNetwork`. What it catches: a
 * change of CoW's API that this build no longer decodes (docs/swaps.md, *How often CoW's API
 * changes*).
 */
class CowApiSepoliaTest {

    private val weth = Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14")
    private val cow = Address.of("0xbe72e441bf55620febc26715db68d3494213d8cb")
    private val from = Address.of("0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58")

    @Test
    fun aQuoteFromCowsSepoliaApiDecodesAndChecks(): Unit = runBlocking {
        assumeTrue("needs -PcowApiNetwork", System.getProperty("serotine.cowApiNetwork") != null)
        val quoting = HttpCowApi.create().quote(Network.SEPOLIA, weth, cow, UInt256.of("10000000000000000"), from)
        println("CoW's quote for 0.01 WETH: $quoting")
        val quoted = assertIs<CowQuoteReading.Quoted>(quoting)
        assertTrue(!quoted.buyAmount.isZero)
    }

    @Test
    fun anOrderCowDoesNotKnowIsUnknown(): Unit = runBlocking {
        assumeTrue("needs -PcowApiNetwork", System.getProperty("serotine.cowApiNetwork") != null)
        val stranger = CowOrderUid.of("0x" + "ab".repeat(32) + from.hex.removePrefix("0x") + "6ad9e7f4")
        assertEquals(CowOrderLife.Unknown, HttpCowApi.create().life(Network.SEPOLIA, stranger))
    }
}
