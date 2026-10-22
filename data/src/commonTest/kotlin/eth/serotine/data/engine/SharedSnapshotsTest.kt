package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class SharedSnapshotsTest {

    private val engine = FakeEngineChain()
    private val time = TestTimeSource()
    private val chain = SharedSnapshots(engine, reuse = 500.milliseconds, timeSource = time)

    @Test
    fun readsWithinTheReuseTimeShareOneStatusCall() {
        chain.snapshot()
        time += 499.milliseconds
        chain.snapshot()
        assertEquals(1, engine.base.snapshots)

        time += 1.milliseconds
        chain.snapshot()
        assertEquals(2, engine.base.snapshots)
    }

    @Test
    fun everythingElseGoesToTheEngine() {
        val address = Address.of("0x" + "11".repeat(20))

        chain.proveBalance(address)

        assertEquals(listOf(address), engine.base.proofRequests)
    }
}
