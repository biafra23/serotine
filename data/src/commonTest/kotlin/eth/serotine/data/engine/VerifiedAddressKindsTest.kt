package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.BalanceReading.Reason
import eth.serotine.domain.repository.KindReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.kotlincrypto.hash.sha3.Keccak256

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedAddressKindsTest {

    private val address = Address.of("0x" + "ab".repeat(20))
    private val delegate = Address.of("0x63c0c19a282a1b52b07dd5a65b58948a07dae32b")
    private val chain = FakeChain()

    private fun TestScope.kinds() = VerifiedAddressKinds(chain, StandardTestDispatcher(testScheduler), 4.seconds)

    @Test
    fun codeTellsAnAccountADelegatedAccountAndAContractApart() {
        assertEquals(AddressKind.Eoa, VerifiedAddressKinds.kindOf(""))
        assertEquals(
            AddressKind.DelegatedEoa(delegate),
            VerifiedAddressKinds.kindOf("ef0100" + delegate.hex.removePrefix("0x")),
        )
        // Not a designator: one byte short, one byte long, another prefix.
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("ef0100" + "ab".repeat(19)))
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("ef0100" + "ab".repeat(21)))
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("ef0200" + "ab".repeat(20)))
        assertEquals(AddressKind.Contract, VerifiedAddressKinds.kindOf("6080604052"))
    }

    @Test
    fun aProvenKindIsLabelledWithItsBlock() = runTest {
        chain.snapshot = running(head = 9)
        chain.codes[address] = ProvenCode("ef0100" + delegate.hex.removePrefix("0x"), blockNumber = 9)

        assertEquals(KindReading.Verified(AddressKind.DelegatedEoa(delegate), 9), kinds().observeKind(address).first())
    }

    @Test
    fun whatCannotBeProvenIsSaidSo() = runTest {
        chain.snapshot = running(head = 9)
        assertEquals(KindReading.Unavailable(Reason.UNPROVEN), kinds().observeKind(address).first())

        chain.snapshot = EngineSnapshot.Booting
        assertEquals(KindReading.Syncing, kinds().observeKind(address).first())

        assertEquals(
            KindReading.Unavailable(Reason.NO_ENGINE),
            VerifiedAddressKinds(null, StandardTestDispatcher(testScheduler), 4.seconds).observeKind(address).first(),
        )
    }

    @Test
    fun codeIsAcceptedOnlyWhenItHashesToTheProvenValue() {
        val contract = "6080604052".fromHex()
        val contractHash = "0x" + Keccak256().digest(contract).toHex()
        var fetched = 0
        val fetch = { fetched++; contract }

        // An absent account and the empty hash have no code to fetch.
        assertEquals(ProvenCode("", 5), provenCode(exists = false, null, 5, fetch))
        assertEquals(ProvenCode("", 5), provenCode(exists = true, "0x" + EMPTY_CODE_HASH.uppercase(), 5, fetch))
        assertEquals(0, fetched)

        assertEquals(ProvenCode("6080604052", 5), provenCode(exists = true, contractHash, 5, fetch))
        // The head moved and the code with it: not the proven code, so unproven.
        assertEquals(null, provenCode(exists = true, "0x" + "ab".repeat(32), 5, fetch))
        assertEquals(null, provenCode(exists = true, contractHash, 5) { null })
        assertEquals(null, provenCode(exists = true, null, 5, fetch))
    }
}
