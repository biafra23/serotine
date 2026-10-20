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
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeLogIndex
import eth.serotine.ui.MemoryHistoryStarts
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
    private val starts = MemoryHistoryStarts()
    private val index = FakeLogIndex()

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
            ObserveSafeHistory(index, starts),
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

    @Test
    fun aSwapAndItsApprovalSayWhatTheyMovedAndThroughWhichRouter() = runTest {
        val router = Address.of("0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e")
        val link = Token(Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789"), "LINK", "ChainLink", 18, "link")
        history.entries.value = listOf(
            HistoryEntry(sent(10, SentAction.TokenApproval(usdc, router, UInt256.ZERO), 600)),
            HistoryEntry(sent(11, SentAction.TokenApproval(usdc, router, UInt256.of(100_000_000)), 700)),
            HistoryEntry(
                sent(
                    12,
                    SentAction.Swap(
                        router = router,
                        sell = TransferAsset.Erc20Token(usdc),
                        amountIn = UInt256.of(100_000_000),
                        buy = TransferAsset.Erc20Token(link),
                        minimumOut = UInt256.of("4288450000000000000"),
                    ),
                    800,
                ),
            ),
        )

        val rows = assertIs<ActivityUiState.Shown>(viewModel().uiState.value).rows

        assertEquals(
            listOf(
                "Swapped 100 USDC for at least 4.28845 LINK",
                "Approved 100 USDC",
                "Allowance reset for a swap",
            ),
            rows.map { it.title },
        )
        assertEquals("On Uniswap · 0x3bFA…e48E", rows.first().detail)
        assertEquals("Router 0x3bFA…e48E", rows.last().detail)
    }

    /** The treasury followed from block 9,400,000, its events indexed over [covered]. */
    private fun treasuryIndexed(covered: LongRange) {
        starts.starts.value = mapOf(treasury.address to HistoryStart.Fixed(9_400_000))
        index.coverage.value = LogCoverageReading.Read(
            mapOf(LogWatchKey.Emitter(treasury.address) to LogCoverage(9_400_000, covered)),
        )
    }

    private fun safeLog(block: Long, topics: List<String>, data: String = "0x", tx: String = "0x" + "cc".repeat(32)) =
        IndexedLog(ReceiptLog(treasury.address, topics, data), block, "0x" + "bb".repeat(32), tx, 0)

    private val received = "0x3d0ce9bfc3ed7d6862dbb28b2dea94561fe714a1b4d019aa8af39730d1ad7c3d"
    private val executionSuccess = "0x442e715f626346e8c54381002da614f62bee8d27386535b2521ec8540898556e"
    private val bobsTopic = "0x" + "00".repeat(12) + bob.hex.removePrefix("0x")

    @Test
    fun aSafesEventsGoInAmongItsSentTransactionsByBlockAndOpenNothing() = runTest {
        history.entries.value = listOf(HistoryEntry(execution, SentOutcome.Included(9_410_000, succeeded = true)))
        treasuryIndexed(9_400_000L..9_420_000L)
        index.logs = listOf(
            safeLog(9_405_000, listOf(received, bobsTopic), "0x" + "0de0b6b3a7640000".padStart(64, '0')),
            safeLog(9_415_000, listOf(received, bobsTopic), "0x" + "1".padStart(64, '0'), tx = "0x" + "dd".repeat(32)),
        )

        val shown = assertIs<ActivityUiState.Shown>(viewModel(treasury.address).uiState.value)

        assertEquals(
            listOf("Received 0.000000000000000001 ETH", "Executed transaction 4", "Received 1 ETH"),
            shown.rows.map { it.title },
        )
        val event = shown.rows.last()
        assertEquals("From 0x5aAe…eAed", event.detail)
        assertEquals("Block 9,405,000", event.time)
        assertEquals(null, event.opens)
        assertEquals(execution.hash, shown.rows[1].opens)
        assertEquals(
            "Transactions sent from this device. Its events on chain from block 9,400,000 on, when it was " +
                "added here. Token transfers are not listed yet.",
            shown.coverage,
        )
    }

    @Test
    fun anEventOfATransactionSentFromHereIsLeftOutForItsRow() = runTest {
        history.entries.value = listOf(HistoryEntry(execution, SentOutcome.Included(9_410_000, succeeded = true)))
        treasuryIndexed(9_400_000L..9_420_000L)
        val topics = listOf(executionSuccess, "0x" + "ab".repeat(32))
        index.logs = listOf(safeLog(9_410_000, topics, "0x" + "0".repeat(64), tx = execution.hash))

        val rows = assertIs<ActivityUiState.Shown>(viewModel(treasury.address).uiState.value).rows

        assertEquals(listOf("Executed transaction 4"), rows.map { it.title })
    }

    @Test
    fun theCoverageSaysWhatIsStillBeingCheckedAndWhenTheSafeIsNotFollowedYet() = runTest {
        treasuryIndexed(9_410_000L..9_420_000L)

        val partly = assertIs<ActivityUiState.Shown>(viewModel(treasury.address).uiState.value)

        assertEquals(
            "Transactions sent from this device. Its events on chain from block 9,400,000 on, when it was " +
                "added here: checked from block 9,410,000 so far, the blocks before are still being checked. " +
                "Token transfers are not listed yet.",
            partly.coverage,
        )
        assertEquals("Nothing listed yet.", partly.empty)

        starts.starts.value = emptyMap()
        val waiting = assertIs<ActivityUiState.Shown>(viewModel(treasury.address).uiState.value)

        assertEquals(true, "listed once the light client is synced" in waiting.coverage)
    }
}
