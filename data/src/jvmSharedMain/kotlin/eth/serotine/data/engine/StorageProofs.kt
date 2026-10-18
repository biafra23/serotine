package eth.serotine.data.engine

/**
 * The block a storage proof the engine verified against the beacon chain is labelled with, or
 * null when it may not be used: what `MyotisNode.proveSlot` reads off a `StorageProofResult`,
 * over its plain values so that the decision is tested on its own.
 *
 * - [verifyMethod] null: nothing tied it to the beacon chain.
 * - `headerChain`: the walk from the proof's block up to an attested one proves the block the
 *   proof anchors to, [peerBlockNumber], itself.
 * - `stateRootMatch` whose [matchedBeaconSlot] is the [finalizedSlot]: [finalizedBlockNumber]; the
 *   [optimisticSlot]: [optimisticBlockNumber].
 * - `stateRootMatch` whose [matchedBeaconSlot] lies above the finalized slot and below the
 *   optimistic one: [peerBlockNumber], the block the account proof anchors to, as balances and
 *   code are labelled. This is the common case, not the odd one: the matched slot is the attested
 *   slot the engine recorded with the anchor, while the result's optimistic slot is the light
 *   client's signature slot, about one past it, so an exact optimistic match seldom happens; and
 *   a proof may match a recent attested head after the optimistic one moved on. The block is not
 *   tied to the optimistic block number for the same reason: the caller's own checks (one block
 *   for every slot of a reading, and a read again when the head moved) take care of a moving
 *   head. A block of 0, or one below the finalized block, is no label.
 * - Any other match (none, below the finalized slot, above the optimistic one): null.
 */
internal fun storageProofBlock(
    verifyMethod: String?,
    matchedBeaconSlot: Long,
    finalizedSlot: Long,
    optimisticSlot: Long,
    peerBlockNumber: Long,
    finalizedBlockNumber: Long,
    optimisticBlockNumber: Long,
): Long? {
    val block = when {
        verifyMethod == null -> return null
        // First: a header-chain walk ends at an attested slot that is not the proof's block.
        verifyMethod == HEADER_CHAIN -> peerBlockNumber
        verifyMethod != STATE_ROOT_MATCH -> return null
        matchedBeaconSlot == finalizedSlot -> finalizedBlockNumber
        matchedBeaconSlot == optimisticSlot -> optimisticBlockNumber
        matchedBeaconSlot in (finalizedSlot + 1)..optimisticSlot ->
            peerBlockNumber.takeIf { it >= finalizedBlockNumber } ?: return null
        else -> return null
    }
    return block.takeIf { it > 0 }
}

private const val HEADER_CHAIN = "headerChain"
private const val STATE_ROOT_MATCH = "stateRootMatch"
