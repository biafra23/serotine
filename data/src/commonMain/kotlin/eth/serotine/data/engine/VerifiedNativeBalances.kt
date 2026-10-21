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
 * cached and refused as [ProvenReads] describes, and proven again only at a head that [activity]
 * cannot vouch for: one whose blocks since the proof it did not all check for a change to the
 * account itself; while no head can be read, the last proof,
 * made in this process or kept in [LastBalancesFile] from an earlier one, as
 * [BalanceReading.LastKnown]. The port never fails, and no answer from
 * anywhere else stands in.
 */
class VerifiedNativeBalances internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    /** Where the last balances are kept across restarts; null keeps them for this process only. */
    lastBalances: LastBalancesFile? = null,
    /** What vouches for an unchanged account between heads; none: a proof at every head. */
    activity: AddressActivity? = null,
) : NativeBalances {

    private val reads = ProvenReads<Address, Wei>(
        chain,
        dispatcher,
        pollInterval,
        kept = lastBalances?.kept<Address, Wei>(id = { "native:${it.hex}" }, units = { it.decimal }, parse = Wei::parse)
            ?: KeptProofs.none(),
        unchangedSince = { address, provenAt, head ->
            activity?.unchangedSince(address, AddressActivity.Kind.ACCOUNT, provenAt, head) == true
        },
    ) { verified, address ->
        verified.proveBalance(address)?.let { proven ->
            Wei.parse(proven.wei)?.let { EngineReading.Proven(it, proven.blockNumber) }
        }
    }

    override fun observeBalance(address: Address): Flow<BalanceReading> = reads.observe(address).map { reading ->
        when (reading) {
            EngineReading.Syncing -> BalanceReading.Syncing
            is EngineReading.Proven -> BalanceReading.Verified(reading.value, reading.blockNumber)
            is EngineReading.LastKnown -> BalanceReading.LastKnown(reading.value, reading.blockNumber, reading.asOf)
            is EngineReading.Unavailable -> BalanceReading.Unavailable(reading.reason)
        }
    }
}
