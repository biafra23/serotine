package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.NativeBalances
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

/**
 * [NativeBalances] over a verified light client: each balance proven by
 * [VerifiedChain.proveBalance] and labelled with the block its proof anchors to, polled,
 * cached and refused as [ProvenReads] describes. The port never fails, and no answer from
 * anywhere else stands in.
 */
class VerifiedNativeBalances internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
) : NativeBalances {

    private val reads = ProvenReads(chain, dispatcher, pollInterval) { verified, address ->
        verified.proveBalance(address)?.let { proven ->
            Wei.parse(proven.wei)?.let { EngineReading.Proven(it, proven.blockNumber) }
        }
    }

    override fun observeBalance(address: Address): Flow<BalanceReading> = reads.observe(address).map { reading ->
        when (reading) {
            EngineReading.Syncing -> BalanceReading.Syncing
            is EngineReading.Proven -> BalanceReading.Verified(reading.value, reading.blockNumber)
            is EngineReading.Unavailable -> BalanceReading.Unavailable(reading.reason)
        }
    }
}
