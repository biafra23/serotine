package eth.serotine.data.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [storageProofBlock]: which block a beacon-verified storage proof is labelled with, if any. */
class StorageProofsTest {

    /** A storage proof's slots and blocks as a real Sepolia engine answered them, on the owner's phone. */
    private fun label(
        method: String? = "stateRootMatch",
        matched: Long = 11_378_990,
        finalizedSlot: Long = 11_378_912,
        optimisticSlot: Long = 11_378_991,
        peerBlock: Long = 11_937_375,
        finalizedBlock: Long = 11_937_299,
        optimisticBlock: Long = 11_937_375,
    ) = storageProofBlock(method, matched, finalizedSlot, optimisticSlot, peerBlock, finalizedBlock, optimisticBlock)

    @Test
    fun aMatchJustBehindTheOptimisticSlotIsTheBlockTheProofAnchorsTo() {
        // The attested slot the anchor recorded, one behind the light client's signature slot:
        // every Safe slot on Sepolia was proven so, and thrown away before.
        assertEquals(11_937_375, label())
        // Three slots behind (missed slots between): the same.
        assertEquals(
            11_937_352,
            storageProofBlock(
                "stateRootMatch",
                matchedBeaconSlot = 11_378_965,
                finalizedSlot = 11_378_880,
                optimisticSlot = 11_378_968,
                peerBlockNumber = 11_937_352,
                finalizedBlockNumber = 11_937_268,
                optimisticBlockNumber = 11_937_352,
            ),
        )
    }

    @Test
    fun aProofOfAHeadTheOptimisticOneMovedPastIsStillItsOwnBlock() {
        // Not tied to the optimistic block: the reading's one-block check and its retry handle that.
        assertEquals(11_937_372, label(peerBlock = 11_937_372, optimisticBlock = 11_937_376))
    }

    @Test
    fun anExactMatchIsTheAnchorsOwnBlock() {
        assertEquals(11_937_299, label(matched = 11_378_912), "the finalized slot: the finalized block")
        assertEquals(11_937_375, label(matched = 11_378_991, peerBlock = 1), "the optimistic slot: its block")
    }

    @Test
    fun aHeaderChainWalkProvesThePeersBlock() {
        assertEquals(11_937_300, label(method = "headerChain", matched = 11_000_000, peerBlock = 11_937_300))
    }

    @Test
    fun aMatchOutsideTheVerifiedRangeOrNoMatchIsNoLabel() {
        assertNull(label(matched = 11_378_911), "below the finalized slot")
        assertNull(label(matched = 11_378_992), "beyond the optimistic slot")
        assertNull(label(matched = -1), "no attestation matched")
    }

    @Test
    fun aFailedOrUnknownMatchOrAnImplausibleBlockIsNoLabel() {
        assertNull(label(method = null), "not tied to the beacon chain")
        assertNull(label(method = "somethingElse"))
        assertNull(label(peerBlock = 0))
        assertNull(label(peerBlock = 11_937_298), "below the finalized block")
        assertNull(label(method = "headerChain", peerBlock = 0))
    }
}
