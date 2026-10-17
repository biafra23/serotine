package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedTokenBalancesTest {

    private val holder = Address.of("0x" + "ab".repeat(20))
    private val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
    private val chain = FakeChain().apply { snapshot = running(head = 9) }
    private val calldata = Abi.balanceOf(holder).toHex()

    private fun TestScope.balances() = VerifiedTokenBalances(chain, StandardTestDispatcher(testScheduler), 4.seconds)

    @Test
    fun balanceOfIsTheSelectorAndThePaddedHolder() {
        assertEquals("70a08231" + "0".repeat(24) + "ab".repeat(20), calldata)
    }

    @Test
    fun aVerifiedWordIsTheBalanceLabelledWithItsBlock() = runTest {
        // 1,250 USDC: 1_250_000_000 units at six decimals.
        chain.calls[usdc to calldata] = ProvenCall("0".repeat(56) + "4a817c80", reverted = false, blockNumber = 9)

        assertEquals(
            TokenBalanceReading.Verified(TokenUnits.of("1250000000"), 9),
            balances().observeBalance(holder, usdc).first(),
        )
    }

    @Test
    fun aBlockTheEngineDidNotNameIsNoBlock() = runTest {
        chain.calls[usdc to calldata] = ProvenCall("0".repeat(64), reverted = false, blockNumber = null)

        val reading = balances().observeBalance(holder, usdc).first() as TokenBalanceReading.Verified
        assertEquals(TokenUnits.of("0"), reading.units)
        assertNull(reading.blockNumber)
    }

    @Test
    fun aRevertOrSomethingThatIsNoWordIsNotAToken() = runTest {
        chain.calls[usdc to calldata] = ProvenCall("", reverted = true, blockNumber = 9)
        assertEquals(TokenBalanceReading.NotAToken, balances().observeBalance(holder, usdc).first())

        chain.calls[usdc to calldata] = ProvenCall("00", reverted = false, blockNumber = 9)
        assertEquals(TokenBalanceReading.NotAToken, balances().observeBalance(holder, usdc).first())
    }

    @Test
    fun noVerifiedAnswerIsUnproven() = runTest {
        assertEquals(
            TokenBalanceReading.Unavailable(ReadUnavailable.UNPROVEN),
            balances().observeBalance(holder, usdc).first(),
        )
    }

    @Test
    fun wordsBecomeExactDecimals() {
        assertEquals("0", Abi.toDecimal(ByteArray(32)))
        assertEquals("255", Abi.toDecimal(byteArrayOf(-1)))
        // 2^256 - 1, the largest uint256.
        assertEquals(
            "115792089237316195423570985008687907853269984665640564039457584007913129639935",
            Abi.toDecimal(ByteArray(32) { -1 }),
        )
        assertNull(Abi.uint256("0x" + "0".repeat(62)), "no 0x, exactly one word")
        assertNull(Abi.uint256("0".repeat(128)), "two words are no amount")
    }
}
