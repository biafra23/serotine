package eth.serotine.domain.model

/**
 * What a Safe's own log says it did (issue #85): one variant per event Safe 1.3.0 and 1.4.1 emit,
 * read by [SafeEvents.decode]. Hashes are `0x` and 64 lower-case hex digits.
 */
sealed interface SafeEvent {

    /** It was set up by [initiator] with [owners] and [threshold]: the event of its creation. */
    data class SetUp(val initiator: Address, val owners: List<Address>, val threshold: Long) : SafeEvent

    /** It ran the Safe transaction [safeTxHash]; [succeeded] false when the call failed (its fee paid). */
    data class Executed(val safeTxHash: String, val succeeded: Boolean) : SafeEvent

    /** It received [amount] wei of the native currency from [from], a contract's transfer included. */
    data class Received(val from: Address, val amount: UInt256) : SafeEvent

    /** Its owner [owner] approved the Safe transaction [safeTxHash] on chain (`approveHash`). */
    data class Approved(val safeTxHash: String, val owner: Address) : SafeEvent

    /** [owner] became one of its owners. */
    data class OwnerAdded(val owner: Address) : SafeEvent

    /** [owner] is no longer one of its owners. */
    data class OwnerRemoved(val owner: Address) : SafeEvent

    /** Its threshold is [threshold] now. */
    data class ThresholdChanged(val threshold: Long) : SafeEvent

    /** The module [module] was enabled: it may move the Safe's funds without its owners. */
    data class ModuleEnabled(val module: Address) : SafeEvent

    /** The module [module] was disabled. */
    data class ModuleDisabled(val module: Address) : SafeEvent

    /** Its guard is [guard] now ([Address.ZERO]: none). */
    data class GuardChanged(val guard: Address) : SafeEvent

    /** Its fallback handler is [handler] now ([Address.ZERO]: none). */
    data class FallbackHandlerChanged(val handler: Address) : SafeEvent

    /** The module [module] ran a transaction from it; [succeeded] false when the call failed. */
    data class ModuleExecuted(val module: Address, val succeeded: Boolean) : SafeEvent

    /** It marked the message [messageHash] signed (`signMessage`, through a delegate call). */
    data class MessageSigned(val messageHash: String) : SafeEvent
}

/**
 * Safe 1.3.0's and 1.4.1's events, by their signature hashes. 1.4.1 indexes the hash of an
 * execution and the address of an owner, module, guard or handler change, where 1.3.0 puts it in
 * the data, so each is read in either form, and in no other.
 */
object SafeEvents {

    /** Every event signature hash [decode] reads: the events a Safe's own history is made of. */
    val TOPICS: List<String> by lazy { decoders.keys.toList() }

    /**
     * What a log with [topics] (each `0x` and 64 lower-case hex digits, the signature hash first)
     * and [data] (`0x` and lower-case hex) says, when it is a Safe event in a shape a Safe emits
     * it in; null for anything else. Whether the contract that emitted it is a Safe is the
     * caller's to know.
     */
    fun decode(topics: List<String>, data: String): SafeEvent? {
        val signature = topics.firstOrNull() ?: return null
        val decoder = decoders[signature] ?: return null
        val indexed = topics.drop(1).map { it.removePrefix("0x").hexToBytesOrNull() ?: return null }
        if (indexed.any { it.size != WORD }) return null
        val bytes = data.removePrefix("0x").takeIf { data.startsWith("0x") }?.hexToBytesOrNull() ?: return null
        return decoder(indexed, bytes)
    }

    private const val WORD = 32

    private val decoders: Map<String, (List<ByteArray>, ByteArray) -> SafeEvent?> = mapOf(
        "0x141df868a6331af528e38c83b7aa03edc19be66e37ae67f9285bf4f8e3c6a1a8" to ::setUp,
        "0x442e715f626346e8c54381002da614f62bee8d27386535b2521ec8540898556e" to { indexed, data ->
            hashAndPayment(indexed, data)?.let { SafeEvent.Executed(it, succeeded = true) }
        },
        "0x23428b18acfb3ea64b08dc0c1d296ea9c09702c09083ca5272e64d115b687d23" to { indexed, data ->
            hashAndPayment(indexed, data)?.let { SafeEvent.Executed(it, succeeded = false) }
        },
        "0x3d0ce9bfc3ed7d6862dbb28b2dea94561fe714a1b4d019aa8af39730d1ad7c3d" to { indexed, data ->
            val value = AbiWords.words(data, 1)?.single()
            if (indexed.size != 1 || value == null) null else AbiWords.address(indexed[0])?.let {
                SafeEvent.Received(it, AbiWords.uint(value))
            }
        },
        "0xf2a0eb156472d1440255b0d7c1e19cc07115d1051fe605b0dce69acfec884d9c" to { indexed, data ->
            if (indexed.size != 2 || data.isNotEmpty()) null else AbiWords.address(indexed[1])?.let {
                SafeEvent.Approved(hashOf(indexed[0]), it)
            }
        },
        "0x9465fa0c962cc76958e6373a993326400c1c94f8be2fe3a952adfa7f60b2ea26" to { indexed, data ->
            addressIn(indexed, data)?.let(SafeEvent::OwnerAdded)
        },
        "0xf8d49fc529812e9a7c5c50e69c20f0dccc0db8fa95c98bc58cc9a4f1c1299eaf" to { indexed, data ->
            addressIn(indexed, data)?.let(SafeEvent::OwnerRemoved)
        },
        "0x610f7ff2b304ae8903c3de74c60c6ab1f7d6226b3f52c5161905bb5ad4039c93" to { indexed, data ->
            val word = AbiWords.words(data, 1)?.single()
            if (indexed.isNotEmpty() || word == null) null else AbiWords.long(word)?.let(SafeEvent::ThresholdChanged)
        },
        "0xecdf3a3effea5783a3c4c2140e677577666428d44ed9d474a0b3a4c9943f8440" to { indexed, data ->
            addressIn(indexed, data)?.let(SafeEvent::ModuleEnabled)
        },
        "0xaab4fa2b463f581b2b32cb3b7e3b704b9ce37cc209b5fb4d77e593ace4054276" to { indexed, data ->
            addressIn(indexed, data)?.let(SafeEvent::ModuleDisabled)
        },
        "0x1151116914515bc0891ff9047a6cb32cf902546f83066499bcf8ba33d2353fa2" to { indexed, data ->
            addressIn(indexed, data)?.let(SafeEvent::GuardChanged)
        },
        "0x5ac6c46c93c8d0e53714ba3b53db3e7c046da994313d7ed0d192028bc7c228b0" to { indexed, data ->
            addressIn(indexed, data)?.let(SafeEvent::FallbackHandlerChanged)
        },
        "0x6895c13664aa4f67288b25d7a21d7aaa34916e355fb9b6fae0a139a9085becb8" to { indexed, data ->
            moduleOf(indexed, data)?.let { SafeEvent.ModuleExecuted(it, succeeded = true) }
        },
        "0xacd2c8702804128fdb0db2bb49f6d127dd0181c13fd45dbfe16de0930e2bd375" to { indexed, data ->
            moduleOf(indexed, data)?.let { SafeEvent.ModuleExecuted(it, succeeded = false) }
        },
        "0xe7f4675038f4f6034dfcbbb24c4dc08e4ebf10eb9d257d3d02c0f38d122ac6e4" to { indexed, data ->
            if (indexed.size != 1 || data.isNotEmpty()) null else SafeEvent.MessageSigned(hashOf(indexed[0]))
        },
    )

    /**
     * `SafeSetup(address indexed initiator, address[] owners, uint256 threshold, address initializer,
     * address fallbackHandler)`: the owners' offset, the threshold, two addresses, then the owners.
     */
    private fun setUp(indexed: List<ByteArray>, data: ByteArray): SafeEvent? {
        if (indexed.size != 1 || data.size < 5 * WORD || data.size % WORD != 0) return null
        val initiator = AbiWords.address(indexed[0]) ?: return null
        val words = AbiWords.words(data, data.size / WORD) ?: return null
        val offset = AbiWords.long(words[0])?.takeIf { it % WORD == 0L } ?: return null
        val threshold = AbiWords.long(words[1]) ?: return null
        if (AbiWords.address(words[2]) == null || AbiWords.address(words[3]) == null) return null
        val at = (offset / WORD).toInt().takeIf { offset / WORD < words.size } ?: return null
        val count = AbiWords.long(words[at])?.takeIf { it == (words.size - at - 1).toLong() } ?: return null
        val owners = (1..count.toInt()).map { AbiWords.address(words[at + it]) ?: return null }
        return SafeEvent.SetUp(initiator, owners, threshold)
    }

    /** An execution's hash: indexed with its payment in the data (1.4.1), or both in the data (1.3.0). */
    private fun hashAndPayment(indexed: List<ByteArray>, data: ByteArray): String? = when (indexed.size) {
        1 -> indexed[0].takeIf { data.size == WORD }?.let(::hashOf)
        0 -> AbiWords.words(data, 2)?.first()?.let(::hashOf)
        else -> null
    }

    /** One address: indexed with no data (1.4.1), or as the one word of the data (1.3.0). */
    private fun addressIn(indexed: List<ByteArray>, data: ByteArray): Address? = when (indexed.size) {
        1 -> indexed[0].takeIf { data.isEmpty() }?.let(AbiWords::address)
        0 -> AbiWords.words(data, 1)?.single()?.let(AbiWords::address)
        else -> null
    }

    /** A module execution's module, indexed in both versions. */
    private fun moduleOf(indexed: List<ByteArray>, data: ByteArray): Address? =
        if (indexed.size != 1 || data.isNotEmpty()) null else AbiWords.address(indexed[0])

    private fun hashOf(word: ByteArray): String = "0x" + word.toHex()

    private fun String.hexToBytesOrNull(): ByteArray? =
        if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'a'..'f' }) null else hexToBytes()
}
