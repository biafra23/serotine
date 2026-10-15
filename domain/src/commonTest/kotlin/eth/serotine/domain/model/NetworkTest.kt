package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkTest {

    @Test
    fun sepoliaIsTheOnlyNetwork() {
        // The owner's ruling of 2026-10-15 (CLAUDE.md, *Data sources*). A second entry here
        // is a new ruling first, not an edit to this test.
        assertEquals(listOf(Network.SEPOLIA), Network.entries)
        assertEquals(11_155_111L, Network.SEPOLIA.chainId)
    }
}
