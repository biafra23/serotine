package eth.serotine.data.transfers

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileSentTransfersTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-sent-${Random.nextLong().toULong()}")
    private val file = Path(directory, "sent-transfers.json")
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = "usdc")

    private fun TestScope.record() = FileSentTransfers(file.toString(), StandardTestDispatcher(testScheduler))

    private fun transfer(nonce: Long, asset: TransferAsset = TransferAsset.Native, account: Address = alice) =
        SentTransfer(
            account = account,
            nonce = nonce,
            signed = SignedTransfer(byteArrayOf(2, nonce.toByte(), -1)),
            action = SentAction.Transfer(bob, asset, UInt256.of(1_500_000)),
            maxCost = UInt256.of(21_000L * 30_000_000_000L),
            sentAt = 1_800_000_000 + nonce,
        )

    private suspend fun TestScope.stored() = (record().observe().first() as SentTransferList.Loaded).transfers

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aRecordedTransferSurvivesARestartAsItWasSigned() = runTest {
        val native = transfer(7)
        val token = transfer(8, TransferAsset.Erc20Token(usdc))

        record().record(native)
        record().record(token)

        // The bundled icon's name comes back with the token, for its status to show.
        assertEquals(listOf(native, token), stored())
    }

    @Test
    fun theSameSignedTransferIsRecordedOnce() = runTest {
        record().record(transfer(7))
        record().record(transfer(7))

        assertEquals(1, stored().size)
    }

    @Test
    fun whatIsMinedOrTooOldIsForgottenForItsAccountAlone() = runTest {
        val store = record()
        listOf(transfer(6), transfer(7), transfer(8), transfer(9), transfer(3, account = bob)).forEach {
            store.record(it)
        }

        // Signed at 1_800_000_000 + nonce: 8 is too old, 9 is not.
        assertTrue(store.prune(alice, minedCount = 8, sentBefore = 1_800_000_009))

        assertEquals(listOf(transfer(9), transfer(3, account = bob)), stored())
    }

    @Test
    fun aSafeTransactionsApprovalAndExecutionAreRecordedWithTheirRequest() = runTest {
        val safe = Address.of("0x" + "5a".repeat(20))
        // A made-up signature, of no key anyone holds.
        val signature = SafeSignature.Ecdsa(bob, Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 1))
        val request = SigningRequest.of(
            Network.SEPOLIA,
            safe,
            SafeTx.call(bob, UInt256.of(5), ByteArray(0), nonce = 3),
            listOf(signature),
        )
        val approval = transfer(7).copy(action = SentAction.SafeApproval(request))
        val execution = transfer(8).copy(action = SentAction.SafeExecution(request))

        record().record(approval)
        record().record(execution)

        assertEquals(listOf(approval, execution), stored())
    }

    @Test
    fun aRecordOfTheFirstFormatReadsAsItWasWritten() = runTest {
        SystemFileSystem.createDirectories(directory)
        // What part 11 wrote, before a record could be a Safe transaction's.
        val v1 = """{"version":1,"transfers":[{"account":"${alice.hex}","nonce":7,"raw":"0207ff",""" +
            """"to":"${bob.hex}","token":null,"amount":"1500000","maxCost":"630000000000000","sentAt":1800000007}]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(v1) }

        assertEquals(listOf(transfer(7)), stored())
    }

    @Test
    fun aRecordThatIsBothOrNeitherIsDamaged() = runTest {
        SystemFileSystem.createDirectories(directory)
        val neither = """{"version":2,"transfers":[{"account":"${alice.hex}","nonce":7,"raw":"0207ff",""" +
            """"maxCost":"1","sentAt":1800000007}]}"""
        SystemFileSystem.sink(file).buffered().use { it.writeString(neither) }

        assertEquals(emptyList(), stored())
    }

    @Test
    fun aDamagedRecordIsSetAsideAndStartsEmpty() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":1,"transfers":[{"nonce":"x"}]}""") }

        assertEquals(emptyList(), stored())
    }
}
