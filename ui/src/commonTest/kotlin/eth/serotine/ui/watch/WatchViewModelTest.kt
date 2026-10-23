package eth.serotine.ui.watch

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.usecase.HoldNetwork
import eth.serotine.domain.usecase.NetworkDemand
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeNames
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkLabel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {

    private val networks = MemoryNetworkSettings()
    private val demand = NetworkDemand()

    private val vitalik = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "11".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val accounts = FakeAccounts(daily)
    private val reading = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Eoa, 9_412_118))
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = reading
    }
    private val names = FakeNames()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): WatchViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = WatchViewModel(
            ObserveAddressKind(kinds),
            ObserveAccounts(accounts),
            WatchAddress(accounts, kinds),
            ResolveRecipient(names),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            HoldNetwork(demand),
        )
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
        assertEquals(NetworkLabel.of(Network.SEPOLIA), viewModel.uiState.value.cardNetwork, "what Add adds it on")
        assertTrue(viewModel.uiState.value.canAdd)

        viewModel.add()
        runCurrent()
        assertTrue(viewModel.uiState.value.done)
        assertEquals(
            Account(Network.SEPOLIA, Address.of(vitalik), AccountKind.EOA, "Vitalik", AccountCustody.Watched),
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
        reading.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()
        assertFalse(viewModel.uiState.value.canAdd)
        // A Safe's code may be proven and its storage not: the text blames neither alone.
        assertEquals(
            Detection.Unavailable(
                "What is at this address could not be verified this time: its code, or for a Safe its owners and " +
                    "threshold. Serotine keeps trying.",
            ),
            viewModel.uiState.value.detection,
        )
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
            override fun observeKind(
                network: Network,
                address: Address,
            ): Flow<KindReading> = MutableStateFlow(KindReading.Syncing)
        }
        val viewModel = WatchViewModel(
            ObserveAddressKind(kinds),
            ObserveAccounts(accounts),
            WatchAddress(accounts, syncing),
            ResolveRecipient(names),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            HoldNetwork(demand),
        )
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
            override fun observeKind(network: Network, address: Address): Flow<KindReading> = slow.filterNotNull()
        }
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel =
            WatchViewModel(
                ObserveAddressKind(kinds),
                ObserveAccounts(accounts),
                WatchAddress(accounts, gatedKinds),
                ResolveRecipient(names),
                ObserveEnabledNetworks(networks),
                SetNetworkEnabled(networks),
                HoldNetwork(demand),
            )
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

        viewModel.onAddressChange("vitalik")
        runCurrent()
        assertNull(viewModel.uiState.value.addressError, "a name still being typed")
        assertEquals("A name ends in its domain, as in name.eth.", viewModel.uiState.value.addressNote)

        viewModel.onAddressChange("0x" + "00".repeat(20))
        runCurrent()
        assertEquals(
            "The zero address: nobody holds its key, so there is no account to watch.",
            viewModel.uiState.value.addressError,
        )
        assertNull(viewModel.uiState.value.detection)

        viewModel.onAddressChange("0xzz")
        runCurrent()
        assertEquals(
            "Not an address: an address is 0x and 40 hexadecimal digits.",
            viewModel.uiState.value.addressError,
        )
    }

    @Test
    fun aVerifiedSafeShowsItsOwnersAndTheControlItWillGetAndCanBeAdded() = runTest {
        val bob = Address.of("0x" + "22".repeat(20))
        val module = Address.of("0x" + "33".repeat(20))
        val state = SafeState("1.4.1", l2 = true, listOf(daily.address, bob), 2, 3, listOf(module), guard = null)
        reading.value = KindReading.Verified(AddressKind.Safe(state), 9_412_118)
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        runCurrent()

        assertEquals(
            Detection.Safe(
                address = vitalik,
                title = "Safe 1.4.1 (L2)",
                signers = "2 of 2 owners",
                owners = listOf(Eip55.format(daily.address), Eip55.format(bob)),
                modules = listOf(Eip55.format(module)),
                guard = null,
                checkedAt = "Checked at block 9,412,118",
                // Daily's key is held here: one of the two that must sign.
                control = Control.CO_SIGNER,
                threshold = "2/2",
            ),
            viewModel.uiState.value.detection,
        )
        assertTrue(viewModel.uiState.value.canAdd)

        viewModel.add()
        runCurrent()
        assertEquals(AccountKind.SAFE, accounts.accounts.value.last().kind)
    }

    @Test
    fun aNameIsResolvedVerifiedAndItsAddressAdded() = runTest {
        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", Address.of(vitalik), 9_412_040)
        val viewModel = viewModel()
        viewModel.onAddressChange("vitalik.eth")
        runCurrent()
        assertEquals("Resolving the name with verified calls…", viewModel.uiState.value.addressNote)
        assertNull(viewModel.uiState.value.detection, "no card before the name is resolved")
        assertTrue(names.asked.isEmpty(), "not resolved while the typing may go on")

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "Resolves to $vitalik, verified at finalized block 9,412,040",
            viewModel.uiState.value.addressNote,
        )
        assertNull(viewModel.uiState.value.addressError)
        assertEquals(
            Detection.Account(vitalik, "Checked at block 9,412,118", delegate = null),
            viewModel.uiState.value.detection,
        )
        assertEquals("vitalik.eth", viewModel.uiState.value.nameDefault, "what an empty name field leaves")

        viewModel.add()
        runCurrent()
        assertTrue(viewModel.uiState.value.done)
        assertEquals(listOf("vitalik.eth", "vitalik.eth"), names.asked, "resolved again at Add")
        // Given no name, the account's name follows the ENS name it was added by.
        assertEquals(
            Account(
                Network.SEPOLIA,
                Address.of(vitalik),
                AccountKind.EOA,
                "vitalik.eth",
                AccountCustody.Watched,
                ensName = "vitalik.eth",
            ),
            accounts.accounts.value.last(),
        )
    }

    @Test
    fun onGnosisChainAloneANameIsRefusedAndNeverAsked() = runTest {
        networks.on.value = setOf(Network.GNOSIS)
        val viewModel = viewModel()
        runCurrent()
        assertEquals("Address", viewModel.uiState.value.addressLabel)
        assertEquals(
            "0x and 40 hexadecimal digits: names are not resolved on Gnosis Chain yet",
            viewModel.uiState.value.addressHint,
        )

        viewModel.onAddressChange("vitalik.eth")
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "Names are not resolved on Gnosis Chain yet: enter the address.",
            viewModel.uiState.value.addressError,
        )
        assertTrue(names.asked.isEmpty(), "never resolved on another chain for it")
        assertEquals(false, viewModel.uiState.value.canAdd)
    }

    @Test
    fun aNameIsResolvedOnEthereumAndOnGnosisChainTheAccountIsTheAddressAlone() = runTest {
        networks.on.value = setOf(Network.ETHEREUM, Network.GNOSIS)
        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", Address.of(vitalik), 9_412_040)
        val viewModel = viewModel()
        assertEquals("Address or ENS name", viewModel.uiState.value.addressLabel)
        viewModel.onAddressChange("vitalik.eth")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "This address has no code. Watch it on Ethereum and Gnosis Chain?",
            viewModel.uiState.value.question,
        )

        viewModel.add()
        runCurrent()

        assertTrue(viewModel.uiState.value.done)
        val (onEthereum, onGnosis) = accounts.accounts.value.takeLast(2)
        assertEquals(Network.ETHEREUM to "vitalik.eth", onEthereum.network to onEthereum.ensName)
        assertEquals(Network.GNOSIS to null, onGnosis.network to onGnosis.ensName, "no Gnosis registry was asked")
        assertEquals("vitalik.eth", onGnosis.label, "one name on every network")
    }

    @Test
    fun aNameResolvedOnSepoliaIsNeverWatchedOnGnosisChain() = runTest {
        networks.on.value = setOf(Network.GNOSIS, Network.SEPOLIA)
        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", Address.of(vitalik), 9_412_040)
        val viewModel = viewModel()
        viewModel.onAddressChange("vitalik.eth")
        advanceTimeBy(1_000)
        runCurrent()

        // Sepolia's registry, which anyone can register on: its answer is for the testnet alone.
        assertNull(viewModel.uiState.value.question)
        assertEquals(NetworkLabel.of(Network.SEPOLIA), viewModel.uiState.value.cardNetwork)
        viewModel.add()
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA), accounts.accounts.value.drop(1).map { it.network })
    }

    @Test
    fun turningEthereumOnResolvesTheNameAgainThereBeforeGnosisChainIsOffered() = runTest {
        networks.on.value = setOf(Network.GNOSIS, Network.SEPOLIA)
        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", Address.of(vitalik), 9_412_040)
        val viewModel = viewModel()
        viewModel.onAddressChange("vitalik.eth")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA), names.networks, "Sepolia's registry first")

        viewModel.onTurnOnNetwork(Network.ETHEREUM.chainId)
        runCurrent()
        assertNull(viewModel.uiState.value.question, "the Sepolia answer is not offered on the real networks")
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(Network.SEPOLIA, Network.ETHEREUM), names.networks, "asked again, on Ethereum")
        assertEquals(
            "This address has no code. Watch it on all three networks?",
            viewModel.uiState.value.question,
        )
    }

    @Test
    fun aNameGivenWithAnEnsNameIsTheUsers() = runTest {
        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", Address.of(vitalik), 9_412_040)
        val viewModel = viewModel()
        viewModel.onAddressChange("vitalik.eth")
        viewModel.onNameChange("Vitalik")
        advanceTimeBy(1_000)
        runCurrent()

        viewModel.add()
        runCurrent()
        assertEquals(
            Account(Network.SEPOLIA, Address.of(vitalik), AccountKind.EOA, "Vitalik", AccountCustody.Watched),
            accounts.accounts.value.last(),
        )
    }

    @Test
    fun anAddressTypedHasNoNameToFollow() = runTest {
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        runCurrent()
        assertNull(viewModel.uiState.value.nameDefault)

        viewModel.add()
        runCurrent()
        assertEquals("Account 2", accounts.accounts.value.last().label)
    }

    @Test
    fun aNameThatMovedBeforeTheAddIsNotAddedAndTheCardFollowsIt() = runTest {
        val elsewhere = Address.of("0x" + "44".repeat(20))
        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", Address.of(vitalik), 9_412_040)
        val viewModel = viewModel()
        viewModel.onAddressChange("vitalik.eth")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(viewModel.uiState.value.canAdd)

        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", elsewhere, 9_412_072)
        viewModel.add()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(viewModel.uiState.value.done)
        assertEquals(
            "“vitalik.eth” no longer resolves to the address that was shown: check the address it has now. " +
                "Nothing was added.",
            viewModel.uiState.value.error,
        )
        assertEquals(listOf(daily), accounts.accounts.value, "nothing added")
        assertEquals(Eip55.format(elsewhere), (viewModel.uiState.value.detection as Detection.Account).address)
    }

    @Test
    fun aNameThatCannotBeVerifiedAgainAtTheAddIsNotAddedAndCanBeTriedAgain() = runTest {
        names.names["vitalik.eth"] = NameResolution.Resolved("vitalik.eth", Address.of(vitalik), 9_412_040)
        val viewModel = viewModel()
        viewModel.onAddressChange("vitalik.eth")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(viewModel.uiState.value.canAdd)

        names.names["vitalik.eth"] = NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.add()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(viewModel.uiState.value.done)
        assertEquals(
            "“vitalik.eth” could not be resolved with verified calls again just now. Nothing was added.",
            viewModel.uiState.value.error,
        )
        assertEquals(listOf(daily), accounts.accounts.value, "nothing added")
        // The lookup made again for the card cannot be verified either: no card, and Try again.
        assertNull(viewModel.uiState.value.detection)
        assertTrue(viewModel.uiState.value.canRetryLookup)
        assertFalse(viewModel.uiState.value.canAdd)
    }

    @Test
    fun aNameWithoutAVerifiedAddressSaysWhyAndCanBeTriedAgain() = runTest {
        val viewModel = viewModel()
        names.names["offchain.eth"] = NameResolution.OffchainOnly
        viewModel.onAddressChange("offchain.eth")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "Only an off-chain gateway gives this name's address, and Serotine asks none: it cannot be resolved here.",
            viewModel.uiState.value.addressError,
        )
        assertFalse(viewModel.uiState.value.canRetryLookup, "asking again changes nothing")

        names.names["later.eth"] = NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
        viewModel.onAddressChange("later.eth")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "The name could not be resolved with verified calls this time.",
            viewModel.uiState.value.addressError,
        )
        assertTrue(viewModel.uiState.value.canRetryLookup)
        assertNull(viewModel.uiState.value.detection)
        assertFalse(viewModel.uiState.value.canAdd)

        names.names["later.eth"] = NameResolution.Resolved("later.eth", Address.of(vitalik), 9_412_100)
        viewModel.onRetryLookup()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(viewModel.uiState.value.addressError)
        assertTrue(viewModel.uiState.value.canAdd)
    }

    @Test
    fun noNetworkOnChecksNothingAndSaysSo() = runTest {
        networks.on.value = emptySet()
        val viewModel = viewModel()
        viewModel.onAddressChange(vitalik)
        runCurrent()

        assertEquals(
            "No network is on in this wallet, so the address cannot be checked. Turn one on in Settings → Networks.",
            viewModel.uiState.value.noNetwork,
        )
        assertEquals(null, viewModel.uiState.value.detection)
        assertEquals(false, viewModel.uiState.value.canAdd)
        assertEquals(
            listOf(
                NetworkLabel.of(Network.ETHEREUM),
                NetworkLabel.of(Network.GNOSIS),
                NetworkLabel.of(Network.SEPOLIA),
            ),
            viewModel.uiState.value.offNetworks,
        )

        viewModel.onTurnOnNetwork(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(setOf(Network.SEPOLIA), networks.on.value)
        assertEquals(null, viewModel.uiState.value.noNetwork)
        assertEquals(
            listOf(NetworkLabel.of(Network.ETHEREUM), NetworkLabel.of(Network.GNOSIS)),
            viewModel.uiState.value.offNetworks,
        )
    }
}
