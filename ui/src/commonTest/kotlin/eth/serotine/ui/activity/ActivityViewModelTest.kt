package eth.serotine.ui.activity

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryTransactionHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private val alice = Account(
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Alice",
        AccountCustody.InKeyStore,
    )
    private val treasury = Account(
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.SAFE,
        "Treasury",
        AccountCustody.Watched,
    )
    private val bob = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val history = MemoryTransactionHistory()
    private val accounts = FakeAccounts(alice, treasury)

    private fun sent(nonce: Long, action: SentAction, sentAt: Long, maybeSent: Boolean = false) = SentTransfer(
        account = alice.address,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(2, nonce.toByte())),
        action = action,
        maxCost = UInt256.of(1_000_000),
        sentAt = sentAt,
        maybeSent = maybeSent,
    )

    private val token = sent(7, SentAction.Transfer(bob, TransferAsset.Erc20Token(usdc), UInt256.of(40_500_000)), 300)
    private val native =
        sent(8, SentAction.Transfer(treasury.address, TransferAsset.Native, UInt256.of("250000000000000000")), 400)
    private val execution = sent(
        9,
        SentAction.SafeExecution(
            SigningRequest.of(
                Network.SEPOLIA,
                treasury.address,
                SafeTx.call(bob, UInt256.ONE, ByteArray(0), nonce = 4),
            ),
        ),
        500,
    )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(address: Address = alice.address): ActivityViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ActivityViewModel(
            address,
            ObserveAccountHistory(history),
            ObserveAccounts(accounts),
            Network.SEPOLIA,
        ) { "at $it" }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun eachTransactionNewestFirstWithWhatItDidAndHowItEnded() = runTest {
        history.entries.value = listOf(
            HistoryEntry(token, SentOutcome.Included(9_401_220, succeeded = false)),
            HistoryEntry(native, SentOutcome.Finalized(9_412_118, succeeded = true)),
            HistoryEntry(execution),
        )

        val shown = assertIs<ActivityUiState.Shown>(viewModel().uiState.value)

        assertEquals("Alice", shown.title)
        assertEquals(
            listOf(
                ActivityRow(
                    execution.hash,
                    "Executed a Safe transaction",
                    "Safe nonce 4 · Treasury · 0xfB69…d359",
                    "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                    "at 500",
                    "No block seen to include it yet",
                    ActivityState.WAITING,
                ),
                ActivityRow(
                    native.hash,
                    "Sent 0.25 ETH",
                    "To Treasury · 0xfB69…d359",
                    "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                    "at 400",
                    "Finalized in block 9,412,118",
                    ActivityState.DONE,
                ),
                ActivityRow(
                    token.hash,
                    "Sent 40.5 USDC",
                    "To 0x5aAe…eAed",
                    "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
                    "at 300",
                    "Reverted in block 9,401,220",
                    ActivityState.FAILED,
                ),
            ),
            shown.rows,
        )
    }

    @Test
    fun aSafeSeesWhichOfItsTransactionsWereExecutedAndByWhom() = runTest {
        history.entries.value = listOf(HistoryEntry(token), HistoryEntry(execution))

        val shown = assertIs<ActivityUiState.Shown>(viewModel(treasury.address).uiState.value)

        assertEquals("Treasury", shown.title)
        assertEquals(listOf("Executed transaction 4"), shown.rows.map { it.title })
        assertEquals("By Alice · 0x8f3A…91c2", shown.rows.single().detail)
    }

    @Test
    fun aMaybeSentOneAndANonceUsedOneSaySo() = runTest {
        history.entries.value = listOf(
            HistoryEntry(token.copy(maybeSent = true)),
            HistoryEntry(native, SentOutcome.NonceUsed),
        )

        val rows = assertIs<ActivityUiState.Shown>(viewModel().uiState.value).rows

        assertEquals(listOf(ActivityState.UNKNOWN, ActivityState.UNCONFIRMED), rows.map { it.state })
    }

    @Test
    fun anUnreadableHistorySaysSo() = runTest {
        history.unreadable.value = true

        assertIs<ActivityUiState.Unavailable>(viewModel().uiState.value)
    }

    @Test
    fun nothingSentIsAnEmptyListThatSaysWhatItCovers() = runTest {
        val shown = assertIs<ActivityUiState.Shown>(viewModel().uiState.value)

        assertEquals(emptyList(), shown.rows)
        assertEquals(true, "sent from this device" in shown.coverage)
    }
}
