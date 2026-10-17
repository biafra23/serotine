package eth.serotine.ui.safepropose

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.ProposeSafeTx
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeNames
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.SafeWorld
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.send.AccountChoice
import eth.serotine.ui.send.SendFrom
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** [SafeProposeViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeProposeViewModelTest {

    private val world = SafeWorld()
    private val clock = TestTimeSource()
    private val feeds = FakeFeeds()
    private val keys = object : KeyStore {
        override suspend fun describe() = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE)

        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
            error("not used by these tests")

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")

        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(from: Address = world.safe, asset: Address? = null): SafeProposeViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = SafeProposeViewModel(
            from,
            asset,
            ObserveAccounts(world.accounts),
            ObserveAccountValue(
                ObserveNativeBalance(world.balances),
                ObserveTokenHoldings(world.knownTokens, world.tokenBalances),
                ObserveUsdPrice(feeds),
                ObserveDisplayCurrencies(feeds, MemoryCurrencySetting()),
                Network.SEPOLIA,
            ),
            world.observeSafe,
            DescribeKeyStore(keys),
            ResolveRecipient(FakeNames()),
            ProposeSafeTx(
                world.observeSafe,
                world.balances,
                world.tokenBalances,
                world.inputs,
                world.queue,
                Network.SEPOLIA,
            ),
            SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA),
            Network.SEPOLIA,
            clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun holding() {
        world.balances.of(world.safe).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        world.tokenBalances.of(world.safe, world.usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("5000000"), 99)
    }

    private val SafeProposeViewModel.form get() = assertIs<SafeProposeUiState.Compose>(uiState.value).form
    private val SafeProposeViewModel.review get() = assertIs<SafeProposeUiState.Review>(uiState.value)

    /** Composes 0.1 ETH to the recipient and asks for the review. */
    private fun TestScope.reviewed(): SafeProposeViewModel {
        holding()
        val viewModel = viewModel()
        viewModel.onToChange(Eip55.format(world.recipient))
        viewModel.onAmountChange("0.1")
        runCurrent()
        viewModel.review()
        runCurrent()
        return viewModel
    }

    @Test
    fun theFormIsSendsFromTheSafeWithItsAssetsAndNoFee() = runTest {
        holding()
        val viewModel = viewModel()

        val form = viewModel.form
        assertEquals(SendFrom("Treasury", Eip55.format(world.safe)), form.from)
        assertEquals(null, form.fee, "the account that executes it pays the fee")
        assertEquals(listOf("ETH", "USDC"), form.assets.map { it.symbol })
        assertEquals(
            listOf("Alice", "Bob", "Dave"),
            form.accounts.map { it.label },
            "the wallet's other accounts, the Safe not among them",
        )
    }

    @Test
    fun aTransferIsProposedAtTheSafesNonceAndSignedByAnOwnerHeldHereWhichQueuesIt() = runTest {
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals("Send 0.1 ETH", review.action.title)
        assertEquals("0.1 ETH", review.amount)
        assertEquals(Eip55.format(world.recipient), review.recipient.address)
        assertEquals("Safe nonce 4", review.nonce)
        assertEquals(null, review.queued)
        assertEquals(
            SafeBalanceChange("ETH balance of the Safe", "1 ETH", "0.9 ETH", "verified at block 100"),
            review.balance,
        )
        assertTrue(review.simulation.startsWith("The Safe's call, run as a verified call from the Safe at block 100"))
        assertEquals(listOf(AccountChoice("Alice", Eip55.format(world.alice.address))), review.owners)
        assertEquals(emptyList(), world.queue.requests.value, "nothing is queued before it is signed")

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        val queued = world.queue.requests.value.single()
        assertEquals(listOf(world.alice.address), queued.signers)
        assertEquals(SafeProposeUiState.Proposed("Sepolia", "0x" + queued.hash.hex), viewModel.uiState.value)
        assertEquals(listOf("made-up test password"), world.signer.passwords)
    }

    @Test
    fun aProposalAfterTheQueueSaysWhatRunsBeforeItAndWhatThoseMove() = runTest {
        world.queue.requests.value = listOf(world.request(world.ether(4), world.carol))
        val viewModel = reviewed()

        val review = viewModel.review
        assertEquals("Safe nonce 5", review.nonce)
        assertEquals("1 transaction queued on this device run before it.", review.queued)
        assertEquals("0.1 ETH moved by those queued before it", review.balance.queued)
        assertEquals("0.8 ETH", review.balance.after)
    }

    @Test
    fun anEngineThatWillNotRunACallFromTheSafeLeavesItNotSimulatedAndSaysSo() = runTest {
        world.inputs.simulation = Simulation.Refused
        val viewModel = reviewed()

        assertTrue(viewModel.review.simulation.startsWith("Not simulated:"), viewModel.review.simulation)
    }

    @Test
    fun aProposalThatCannotWorkIsRefusedOnTheForm() = runTest {
        holding()
        val viewModel = viewModel()
        viewModel.onToChange(Eip55.format(world.recipient))
        viewModel.onAmountChange("2")
        runCurrent()

        viewModel.review()
        runCurrent()

        assertEquals(
            "The Safe holds less ETH than that, once the transactions queued on this device before it have moved " +
                "what they move.",
            viewModel.form.error,
        )
    }

    @Test
    fun aProposalOverAMinuteOldIsMadeAgainAtTheTapThatWouldSignIt() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        clock += 61.seconds

        viewModel.sign()
        runCurrent()

        assertEquals(
            "The quote was over a minute old, so it was made again: check it, then sign.",
            viewModel.review.unlock.error,
        )
        assertEquals(emptyList(), world.signer.asked)
    }

    @Test
    fun thePasswordGoesWhenTheScreenIsHidden() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")

        viewModel.onHidden()

        assertEquals("", viewModel.review.unlock.password.text)
    }

    @Test
    fun aSignatureTheQueueCannotSaveIsSaidAndNothingIsKept() = runTest {
        val viewModel = reviewed()
        world.queue.failSaves = true

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertTrue(viewModel.review.error!!.startsWith("Signed, but this device's queue cannot be saved"))
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun backFromTheReviewReturnsToTheFormAsItWas() = runTest {
        val viewModel = reviewed()

        viewModel.edit()

        assertEquals("0.1", viewModel.form.amount)
    }

    @Test
    fun aSafeWithNoOwnerHeldHereCannotPropose() = runTest {
        world.state = world.state.copy(owners = listOf(world.bob.address, world.carol))
        val viewModel = viewModel()

        val cannot = assertIs<SafeProposeUiState.CannotPropose>(viewModel.uiState.value)
        assertTrue(cannot.message.startsWith("No owner of this Safe has its key on this device"), cannot.message)
    }

    @Test
    fun anAccountThatIsNoSafeCannotPropose() = runTest {
        val viewModel = viewModel(from = world.alice.address)

        assertEquals(
            SafeProposeUiState.CannotPropose(
                "Sepolia",
                "Only a Safe proposes a Safe transaction: this account sends with Send.",
            ),
            viewModel.uiState.value,
        )
    }
}
