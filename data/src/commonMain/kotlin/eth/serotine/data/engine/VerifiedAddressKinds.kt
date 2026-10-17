package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

/**
 * [AddressKinds] over a verified light client: an address's code, proven by
 * [VerifiedChain.proveCode], read as no code (an EOA), an EIP-7702 delegation designator (an
 * EOA that has delegated), or anything else (a contract). Polled, cached and refused as
 * [ProvenReads] describes.
 */
class VerifiedAddressKinds internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
) : AddressKinds {

    private val reads = ProvenReads(chain, dispatcher, pollInterval) { verified, address ->
        verified.proveCode(address)?.let { EngineReading.Proven(kindOf(it.codeHex), it.blockNumber) }
    }

    override fun observeKind(address: Address): Flow<KindReading> = reads.observe(address).map { reading ->
        when (reading) {
            EngineReading.Syncing -> KindReading.Syncing
            is EngineReading.Proven -> KindReading.Verified(reading.value, reading.blockNumber)
            is EngineReading.Unavailable -> KindReading.Unavailable(reading.reason)
        }
    }

    internal companion object {
        /** EIP-7702's delegation designator, `0xef0100`, which 20 bytes of address follow. */
        private const val DELEGATION_PREFIX = "ef0100"

        /** What [codeHex] (lower-case hex, no `0x`) makes an address. */
        fun kindOf(codeHex: String): AddressKind = when {
            codeHex.isEmpty() -> AddressKind.Eoa
            codeHex.length == DELEGATION_PREFIX.length + 40 && codeHex.startsWith(DELEGATION_PREFIX) ->
                AddressKind.DelegatedEoa(Address.of("0x" + codeHex.drop(DELEGATION_PREFIX.length)))
            else -> AddressKind.Contract
        }
    }
}
