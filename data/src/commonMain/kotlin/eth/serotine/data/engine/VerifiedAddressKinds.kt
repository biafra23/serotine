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
 * at slot 1, whose sentinel every set-up Safe has written), its guard and, from 1.5.0, its
 * module guard. The lists are what the Safe's own getters return; an entry written outside
 * them (a setup delegatecall can) is not something reading them can rule out.
 *
 * Every numbered slot is a storage proof, and all of them must be of one state, no older than
 * the code's: one verified anchor ([ProofAnchor], the attested slot a proof matched; a block
 * label alone can be a peer's report) and one block, the one the reading is labelled with. A read
 * the head moved under is made again at once, up to [SAFE_ATTEMPTS] times. The guards are kept at hashed slots no storage
 * proof names, so they are verified reads at the head, which may be a head later than that
 * label. Anything that does not match is a contract, never a Safe. A Safe's reading stands
 * for [SAFE_KEPT_FOR_HEADS] heads (a Safe takes a dozen proofs, and its owners rarely change);
 * any other kind's for one.
 */
class VerifiedAddressKinds internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    private val safes: SafeDeployments.Table,
) : AddressKinds {

    private val reads = ProvenReads<Address, AddressKind>(
        chain,
        dispatcher,
        pollInterval,
        keptForHeads = { kind -> if (kind is AddressKind.Safe) SAFE_KEPT_FOR_HEADS else 1 },
        sharedForHeads = SAFE_KEPT_FOR_HEADS,
    ) { verified, address ->
        val code = verified.proveCode(address) ?: return@ProvenReads null
        when (val kind = kindOf(code.codeHex)) {
            AddressKind.Contract ->
                if (codeHash(code.codeHex) in safes.proxyCodeHashes) {
                    readSafe(verified, address, code)
                } else {
                    EngineReading.Proven(kind, code.blockNumber)
                }
            else -> EngineReading.Proven(kind, code.blockNumber)
        }
    }

    /** The Safe at [safe], whose [code] is proven, read again at once when the head moved under a read. */
    private fun readSafe(chain: VerifiedChain, safe: Address, code: ProvenCode): EngineReading<AddressKind>? {
        repeat(SAFE_ATTEMPTS) {
            val storage = SafeStorage(chain, safe, code)
            val reading = storage.read()
            if (reading != null || !storage.straddled) return reading
        }
        return null
    }

    /**
     * One read of the Safe storage at [safe]: every slot proven against one anchor, the verified
     * attested slot a proof matched (a block label alone can be a peer's report, so two slots
     * with equal labels and different anchors are of two states), shown with one block, and not
     * of a state before the [code]'s: their anchors compared where both matched an attested slot,
     * and their block labels otherwise (an account proof walked by header chain has no anchor of
     * its own to compare).
     */
    private inner class SafeStorage(
        private val chain: VerifiedChain,
        private val safe: Address,
        private val code: ProvenCode,
    ) {
        private val blocks = mutableSetOf<Long>()
        private val anchors = mutableSetOf<ProofAnchor>()

        /** Whether the slots read were proven at more than one block (or before the code): the head moved. */
        var straddled = false
            private set

        /** The word at [slot] (or under [key] in the mapping there); null: unproven. */
        private fun word(slot: Long, key: Address? = null): String? =
            chain.proveSlot(safe, slot, key)?.also {
                blocks += it.blockNumber
                anchors += it.anchor
            }?.valueHex

        fun read(): EngineReading<AddressKind>? {
            val singleton = addressIn(word(SINGLETON_SLOT) ?: return null)?.let { safes.singletons[it] }
                ?: return proven(AddressKind.Contract)
            val owners = when (val walked = walk(OWNERS_SLOT, MAX_OWNERS)) {
                Walk.Unproven -> return null
                Walk.Broken -> return proven(AddressKind.Contract)
                is Walk.Entries -> walked.addresses
            }
            // A proven word that is no small number is no Safe's count.
            val ownerCount = number(word(OWNER_COUNT_SLOT) ?: return null) ?: return proven(AddressKind.Contract)
            val threshold = number(word(THRESHOLD_SLOT) ?: return null) ?: return proven(AddressKind.Contract)
            // Not set up, or not consistent: a contract, before any more is read.
            if (owners.isEmpty() || ownerCount != owners.size.toLong() || threshold !in 1..owners.size.toLong()) {
                return proven(AddressKind.Contract)
            }
            val nonce = number(word(NONCE_SLOT) ?: return null) ?: return proven(AddressKind.Contract)
            val modules = when (val walked = walk(MODULES_SLOT, MAX_MODULES)) {
                Walk.Unproven -> return null
                Walk.Broken -> return proven(AddressKind.Contract)
                is Walk.Entries -> walked.addresses
            }
            // Head reads: their slots are hashes no storage proof names. Only a version that
            // consults a module guard has one: a word in that slot of an older Safe is nothing.
            val guard = when (val read = guardAt(GUARD_SLOT)) {
                Guard.Unproven -> return null
                Guard.Broken -> return proven(AddressKind.Contract)
                is Guard.At -> read.address
            }
            val moduleGuard = if (!singleton.moduleGuard) {
                null
            } else {
                when (val read = guardAt(MODULE_GUARD_SLOT)) {
                    Guard.Unproven -> return null
                    Guard.Broken -> return proven(AddressKind.Contract)
                    is Guard.At -> read.address
                }
            }
            val state = SafeState(
                singleton.version,
                singleton.l2,
                owners,
                threshold.toInt(),
                nonce,
                modules,
                guard,
                moduleGuard,
            )
            return proven(AddressKind.Safe(state))
        }

        /** The guard kept at [slot]: an address, none, or a word that is no address. */
        private fun guardAt(slot: ByteArray): Guard {
            val word = chain.storageAt(safe, slot) ?: return Guard.Unproven
            if (word.all { it == '0' }) return Guard.At(null)
            return addressIn(word)?.let { Guard.At(it) } ?: Guard.Broken
        }

        /**
         * [kind], labelled with the one block every slot is shown with, when every slot shares one
         * anchor and that state is not before the code's; null, and [straddled], otherwise.
         */
        private fun proven(kind: AddressKind): EngineReading<AddressKind>? {
            val anchor = anchors.singleOrNull()
            val block = blocks.singleOrNull()
            if (anchor == null || block == null || !notBeforeCode(anchor, block)) {
                straddled = true
                return null
            }
            return EngineReading.Proven(kind, block)
        }

        /**
         * Whether the state at [anchor] (shown as [block]) is not before the code's: as their
         * anchors tell ([ProofAnchor.notBefore]), else as their block labels do.
         */
        private fun notBeforeCode(anchor: ProofAnchor, block: Long): Boolean =
            code.anchor?.let { anchor.notBefore(it) } ?: (block >= code.blockNumber)

        /**
         * The linked list of the mapping at [slot] (owners, modules), walked from the sentinel
         * `0x1` back to it: no more than [max] entries, each an address. Setup writes both
         * sentinels, so an empty one is no list a Safe keeps.
         */
        private fun walk(slot: Long, max: Int): Walk {
            val found = mutableListOf<Address>()
            var next = word(slot, SENTINEL) ?: return Walk.Unproven
            while (true) {
                if (next.all { it == '0' }) return Walk.Broken
                val entry = addressIn(next) ?: return Walk.Broken
                if (entry == SENTINEL) return Walk.Entries(found)
                if (entry in found || found.size == max) return Walk.Broken
                found += entry
                next = word(slot, entry) ?: return Walk.Unproven
            }
        }
    }

    /** What reading a guard gave: one or none ([At]), or that it could not be read, or that it is no address. */
    private sealed interface Guard {
        data class At(val address: Address?) : Guard
        data object Unproven : Guard
        data object Broken : Guard
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

        /** Where a Safe keeps its guard (1.3.0 on), and its module guard (1.5.0 on). */
        internal val GUARD_SLOT = Keccak256().digest("guard_manager.guard.address".encodeToByteArray())
        internal val MODULE_GUARD_SLOT = Keccak256().digest("module_manager.module_guard.address".encodeToByteArray())

        /** Heads a Safe's proof stands: about a minute of Sepolia blocks. */
        private const val SAFE_KEPT_FOR_HEADS = 5L

        /** Reads of a Safe made at once when the head moved under one. */
        private const val SAFE_ATTEMPTS = 3

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
