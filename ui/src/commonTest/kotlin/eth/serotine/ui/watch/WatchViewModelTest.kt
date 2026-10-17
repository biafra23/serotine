package eth.serotine.ui.watch

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.FakeAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {

    private val vitalik = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    private val daily = Account(Address.of("0x" + "11".repeat(20)), AccountKind.EOA, "Daily", AccountCustody.InKeyStore)
    private val accounts = FakeAccounts(daily)
    private val reading = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Eoa, 9_412_118))
    private val kinds = object : AddressKinds {
        override fun observeKind(address: Address): Flow<KindReading> = reading
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): WatchViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel =
            WatchViewModel(ObserveAddressKind(kinds), ObserveAccount(accounts), WatchAddress(accounts, kinds))
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun aVerifiedAccountCanBeAddedAndLeavesTheScreen() = runTest {
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        viewModel.onNameChange("Vitalik")
        runCurrent()

        assertEquals(
            Detection.Account(vitalik, "Checked at block 9,412,118", delegate = null),
            viewModel.uiState.value.detection,
        )
        assertTrue(viewModel.uiState.value.canAdd)

        viewModel.add()
        runCurrent()
        assertTrue(viewModel.uiState.value.done)
        assertEquals(
            Account(Address.of(vitalik), AccountKind.EOA, "Vitalik", AccountCustody.Watched),
            accounts.accounts.value.last(),
        )
    }

    @Test
    fun aDelegationIsNamedWithItsDelegate() = runTest {
        reading.value = KindReading.Verified(AddressKind.DelegatedEoa(daily.address), 7)
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        runCurrent()

        val detection = viewModel.uiState.value.detection as Detection.Account
        assertEquals("0x1111111111111111111111111111111111111111", detection.delegate)
    }

    @Test
    fun aContractOrAnUncheckedAddressCannotBeAdded() = runTest {
        val viewModel = viewModel()
        reading.value = KindReading.Verified(AddressKind.Contract, 7)
        viewModel.onAddressChange(vitalik)
        runCurrent()
        assertTrue(viewModel.uiState.value.detection is Detection.Contract)
        assertFalse(viewModel.uiState.value.canAdd)

        reading.value = KindReading.Syncing
        runCurrent()
        assertTrue(viewModel.uiState.value.detection is Detection.Unavailable)
        reading.value = KindReading.Unavailable(BalanceReading.Reason.UNPROVEN)
        runCurrent()
        assertFalse(viewModel.uiState.value.canAdd)
    }

    @Test
    fun anAddressInTheWalletIsSaidSo() = runTest {
        val viewModel = viewModel()
        viewModel.onAddressChange(daily.address.hex)
        runCurrent()

        assertEquals(Detection.InWallet("Daily"), viewModel.uiState.value.detection)
        assertFalse(viewModel.uiState.value.canAdd)
    }

    @Test
    fun aKindThatTurnsUnverifiableBeforeTheAddIsRefusedAndSaid() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        // The card shows an account; at Add the use case proves again, and cannot.
        val syncing = object : AddressKinds {
            override fun observeKind(address: Address): Flow<KindReading> = MutableStateFlow(KindReading.Syncing)
        }
        val viewModel =
            WatchViewModel(ObserveAddressKind(kinds), ObserveAccount(accounts), WatchAddress(accounts, syncing))
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.onAddressChange(vitalik)
        runCurrent()
        viewModel.add()
        runCurrent()

        assertEquals("The address cannot be checked right now. Nothing was added.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.done)
        viewModel.onNameChange("x")
        runCurrent()
        assertNull(viewModel.uiState.value.error, "an edit clears what the last add said")
    }

    @Test
    fun anOverlongNameIsSaidOnTheNameField() = runTest {
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        viewModel.onNameChange("x".repeat(41))
        runCurrent()
        viewModel.add()
        runCurrent()

        assertEquals("Use at most 40 characters", viewModel.uiState.value.nameError)
    }

    @Test
    fun aListThatCannotBeSavedIsSaid() = runTest {
        accounts.failSaves = true
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        runCurrent()
        viewModel.add()
        runCurrent()

        assertEquals("The account could not be saved. Nothing was added.", viewModel.uiState.value.error)
    }

    @Test
    fun aSecondAddBeforeTheFirstShowsIsIgnored() = runTest {
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        runCurrent()

        // Two taps in one frame: the second before the first's state reaches uiState.
        viewModel.add()
        viewModel.add()
        runCurrent()

        assertTrue(viewModel.uiState.value.done)
        assertNull(viewModel.uiState.value.error, "no 'already in your wallet' from a second add")
        assertEquals(1, accounts.accounts.value.count { it.address == Address.of(vitalik) })
    }

    @Test
    fun editsWhileAnAddRunsAreIgnored() = runTest {
        val slow = MutableStateFlow<KindReading?>(null)
        val gatedKinds = object : AddressKinds {
            override fun observeKind(address: Address): Flow<KindReading> = slow.filterNotNull()
        }
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel =
            WatchViewModel(ObserveAddressKind(kinds), ObserveAccount(accounts), WatchAddress(accounts, gatedKinds))
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.onAddressChange(vitalik)
        viewModel.onNameChange("Vitalik")
        runCurrent()
        viewModel.add()
        runCurrent()
        assertTrue(viewModel.uiState.value.adding)

        viewModel.onAddressChange("0x" + "22".repeat(20))
        viewModel.onNameChange("For B")
        runCurrent()
        assertEquals(vitalik, viewModel.uiState.value.address, "the form stays what is being added")

        slow.value = KindReading.Verified(AddressKind.Eoa, 9)
        runCurrent()
        assertTrue(viewModel.uiState.value.done)
        assertEquals("Vitalik", accounts.accounts.value.last().label)
    }

    @Test
    fun theInputIsCheckedOnlyOnceItIsClearlyNoAddress() = runTest {
        val viewModel = viewModel()

        viewModel.onAddressChange("0xd8dA6B")
        runCurrent()
        assertNull(viewModel.uiState.value.addressError, "still typing")
        assertNull(viewModel.uiState.value.detection)

        viewModel.onAddressChange("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96046")
        runCurrent()
        assertEquals(
            "The capitals do not match this address's checksum: check it for a typo.",
            viewModel.uiState.value.addressError,
        )

        viewModel.onAddressChange("vitalik.eth")
        runCurrent()
        assertEquals("Enter the address itself; names (ENS) come with sending.", viewModel.uiState.value.addressError)

        viewModel.onAddressChange("0xzz")
        runCurrent()
        assertEquals(
            "Not an address: an address is 0x and 40 hexadecimal digits.",
            viewModel.uiState.value.addressError,
        )
    }
}
