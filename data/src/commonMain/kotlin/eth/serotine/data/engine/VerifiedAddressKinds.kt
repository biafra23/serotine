package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.data.safe.SafeDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.SafeState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kotlincrypto.hash.sha3.Keccak256
import kotlin.time.Duration

/**
 * [AddressKinds] over a verified light client: an address's code, proven by
 * [VerifiedChain.proveCode], read as no code (an EOA), an EIP-7702 delegation designator (an
 * EOA that has delegated), a Safe, or anything else (a contract). Polled, cached and refused
 * as [ProvenReads] describes.
 *
 * A Safe (docs/wallet-ux.md, *Safes*) is code whose hash is a pinned SafeProxy's ([safes]),
 * whose slot 0 names a pinned singleton, and whose storage holds a set-up Safe: its owners, the
 * linked list of the mapping at slot 2 walked from the sentinel `0x1`, as many as slot 3
 * counts, a threshold (slot 4) of at most that many, its nonce (slot 5), its modules (the list
 * at slot 1) and its guard. Every slot is a storage proof, and all of them must anchor to one
 * block, the one the reading is labelled with: a read the head moved under is made again at
 * the next. Anything that does not match is a contract, never a Safe.
 */
class VerifiedAddressKinds internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    private val safes: SafeDeployments.Table,
) : AddressKinds {

    private val reads = ProvenReads<Address, AddressKind>(chain, dispatcher, pollInterval) { verified, address ->
        val code = verified.proveCode(address) ?: return@ProvenReads null
        when (val kind = kindOf(code.codeHex)) {
            AddressKind.Contract ->
                if (codeHash(code.codeHex) in safes.proxyCodeHashes) {
                    SafeStorage(verified, address).read()
                } else {
                    EngineReading.Proven(kind, code.blockNumber)
                }
            else -> EngineReading.Proven(kind, code.blockNumber)
        }
    }

    /** One read of the Safe storage at [safe]: every slot proven, all at one block. */
    private inner class SafeStorage(private val chain: VerifiedChain, private val safe: Address) {
        private val blocks = mutableSetOf<Long>()

        /** The word at [slot] (or under [key] in the mapping there); null: unproven. */
        private fun word(slot: Long, key: Address? = null): String? =
            chain.proveSlot(safe, slot, key)?.also { blocks += it.blockNumber }?.valueHex

        fun read(): EngineReading<AddressKind>? {
            val singletonWord = word(SINGLETON_SLOT) ?: return null
            val singleton = addressIn(singletonWord)?.let { safes.singletons[it] }
                ?: return proven(AddressKind.Contract)
            val owners = when (val walked = walk(OWNERS_SLOT, MAX_OWNERS)) {
                Walk.Unproven -> return null
                Walk.Broken -> return proven(AddressKind.Contract)
                is Walk.Entries -> walked.addresses
            }
            val ownerCount = word(OWNER_COUNT_SLOT)?.let(::number) ?: return null
            val threshold = word(THRESHOLD_SLOT)?.let(::number) ?: return null
            val nonce = word(NONCE_SLOT)?.let(::number) ?: return null
            val modules = when (val walked = walk(MODULES_SLOT, MAX_MODULES)) {
                Walk.Unproven -> return null
                Walk.Broken -> return proven(AddressKind.Contract)
                is Walk.Entries -> walked.addresses
            }
            // Not a block-labelled proof, but a verified read; only an address or none is a guard.
            val guardWord = chain.storageAt(safe, GUARD_SLOT) ?: return null
            val guard = if (guardWord.all { it == '0' }) {
                null
            } else {
                addressIn(guardWord) ?: return proven(AddressKind.Contract)
            }
            // Not set up (no owners, no threshold) or not consistent: a contract, not a Safe.
            if (owners.isEmpty() || ownerCount != owners.size.toLong() || threshold !in 1..owners.size.toLong()) {
                return proven(AddressKind.Contract)
            }
            val state = SafeState(singleton.version, singleton.l2, owners, threshold.toInt(), nonce, modules, guard)
            return proven(AddressKind.Safe(state))
        }

        /** [kind], labelled with the one block every slot was proven at; null when they differ. */
        private fun proven(kind: AddressKind): EngineReading<AddressKind>? =
            blocks.singleOrNull()?.let { EngineReading.Proven(kind, it) }

        /**
         * The linked list of the mapping at [slot] (owners, modules), walked from the sentinel
         * `0x1` back to it: no more than [max] entries, each an address; none at all when the
         * sentinel's entry is empty (modules never set up).
         */
        private fun walk(slot: Long, max: Int): Walk {
            val found = mutableListOf<Address>()
            var next = word(slot, SENTINEL) ?: return Walk.Unproven
            if (next.all { it == '0' }) return Walk.Entries(found)
            while (true) {
                val entry = addressIn(next) ?: return Walk.Broken
                if (entry == SENTINEL) return Walk.Entries(found)
                if (entry in found || found.size == max) return Walk.Broken
                found += entry
                next = word(slot, entry) ?: return Walk.Unproven
                if (next.all { it == '0' }) return Walk.Broken
            }
        }
    }

    /** What walking a list gave: its entries, or that a proof failed, or that the list is no list. */
    private sealed interface Walk {
        data class Entries(val addresses: List<Address>) : Walk
        data object Unproven : Walk
        data object Broken : Walk
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

        // The Safe storage layout of 1.3.0 to 1.5.0 (Singleton, ModuleManager, OwnerManager, Safe).
        private const val SINGLETON_SLOT = 0L
        private const val MODULES_SLOT = 1L
        private const val OWNERS_SLOT = 2L
        private const val OWNER_COUNT_SLOT = 3L
        private const val THRESHOLD_SLOT = 4L
        private const val NONCE_SLOT = 5L

        /** `keccak256("guard_manager.guard.address")`, where a Safe keeps its guard. */
        private val GUARD_SLOT = "4a204f620c8c5ccdca3fd54d003badd85ba500436a431f0cbda4f558c93c34c8".fromHex()

        /** The lists' sentinel, `0x…01`. */
        private val SENTINEL = Address.of("0x" + "0".repeat(39) + "1")

        /** Far more owners and modules than a Safe has: a list longer than this is no list read here. */
        private const val MAX_OWNERS = 100
        private const val MAX_MODULES = 50

        /** The address a storage word holds: its low 20 bytes, the rest zero; null when it is no address. */
        private fun addressIn(word: String): Address? = word
            .takeIf { it.length == WORD_HEX_DIGITS && it.take(ADDRESS_PADDING).all { c -> c == '0' } }
            ?.let { Address.of("0x" + it.drop(ADDRESS_PADDING)) }

        /** A storage word as a number, when it is a small one. */
        private fun number(word: String): Long? = Abi.uint256(word)?.toLongOrNull()

        /** The Keccak-256 of [codeHex], as lower-case hex. */
        private fun codeHash(codeHex: String): String = Keccak256().digest(codeHex.fromHex()).toHex()

        private const val WORD_HEX_DIGITS = 64
        private const val ADDRESS_PADDING = 24

        /** What [codeHex] (lower-case hex, no `0x`) makes an address. */
        fun kindOf(codeHex: String): AddressKind = when {
            codeHex.isEmpty() -> AddressKind.Eoa
            codeHex.length == DELEGATION_PREFIX.length + 40 && codeHex.startsWith(DELEGATION_PREFIX) ->
                AddressKind.DelegatedEoa(Address.of("0x" + codeHex.drop(DELEGATION_PREFIX.length)))
            else -> AddressKind.Contract
        }
    }
}
