package eth.serotine.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.ui.accountdetail.ACCOUNT_ADDRESS_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_TOTAL_TAG
import eth.serotine.ui.addtoken.ADD_TOKEN_ADDRESS_TAG
import eth.serotine.ui.assets.ASSETS_ACCOUNT_LIST_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_FIELD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_IMPORT_BUTTON_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_PASSWORD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_REPEAT_TAG
import eth.serotine.ui.receive.RECEIVE_ADDRESS_TAG
import eth.serotine.ui.renameaccount.RENAME_FIELD_TAG
import eth.serotine.ui.settings.SETTINGS_VERSION_TAG
import eth.serotine.ui.tokens.TOKENS_ROW_TAG
import eth.serotine.ui.update.UPDATE_REQUIRED_TAG
import eth.serotine.ui.watch.WATCH_ADDRESS_TAG
import eth.serotine.ui.watch.WATCH_NAME_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.test.assertEquals

/** The shared [App] end to end, over fake ports: the tabs, the account screens and their dialogs. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigationTest {

    // The test drives the UI from its own thread, so that thread must count as the main one,
    // for viewModelScope and for the back stack's lifecycle checks alike. An OUTER rule: the
    // compose rule's teardown still dispatches to Main while it disposes the composition.
    @get:Rule(order = 0)
    val mainDispatcher = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())

        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val accounts = MutableStateFlow<List<Account>>(emptyList())
    private val deletedKeys = mutableListOf<Address>()
    private var failKeyDeletes = false
    private val navController = mutableListOf<NavHostController>()
    private val tokenMetadata = FakeMetadata()
    private val userTokens = MemoryUserTokens()
    private val report = MutableStateFlow<NetworkUpgrade?>(null)
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val graph = AppGraph(
        accounts = object : AccountRepository {
            override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }

            override suspend fun add(account: Account): AccountAdd {
                accounts.update { it + account }
                return AccountAdd.Added
            }

            override suspend fun rename(address: Address, label: String): AccountChange {
                accounts.update { list -> list.map { if (it.address == address) it.copy(label = label) else it } }
                return AccountChange.Changed
            }

            override suspend fun setCustody(address: Address, custody: AccountCustody): AccountChange {
                accounts.update { list -> list.map { if (it.address == address) it.copy(custody = custody) else it } }
                return AccountChange.Changed
            }

            override suspend fun remove(address: Address): AccountChange {
                accounts.update { list -> list.filterNot { it.address == address } }
                return AccountChange.Changed
            }
        },
        keys = object : KeyStore {
            override suspend fun newMnemonic(): NewMnemonic = NewMnemonic.Unavailable

            override suspend fun describe() = KeyStoreDescription.Ready(passwordSet = false, KeyProtection.HARDWARE)

            override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
                KeyImport.Stored(Address.of("0x" + "ab".repeat(20)), KeyProtection.HARDWARE)

            override suspend fun delete(address: Address): KeyDeletion {
                deletedKeys += address
                return if (failKeyDeletes) KeyDeletion.Failed else KeyDeletion.Deleted
            }
        },
        nativeBalances = object : NativeBalances {
            override fun observeBalance(address: Address): Flow<BalanceReading> = flowOf(BalanceReading.Syncing)
        },
        tokenCatalog = object : TokenCatalog {
            override fun observeTokens(): Flow<List<Token>> = flowOf(emptyList())
        },
        tokenBalances = object : TokenBalances {
            override fun observeBalance(holder: Address, token: Address): Flow<TokenBalanceReading> =
                flowOf(TokenBalanceReading.Syncing)
        },
        tokenMetadata = tokenMetadata,
        userTokens = userTokens,
        priceFeeds = FakeFeeds(),
        displayCurrency = MemoryCurrencySetting(),
        addressKinds = object : AddressKinds {
            override fun observeKind(address: Address): Flow<KindReading> =
                flowOf(KindReading.Verified(AddressKind.Eoa, 9_412_118))
        },
        lightClient = object : LightClient {
            override fun observeStatus(): Flow<LightClientStatus> = status
        },
        networkUpgrades = object : NetworkUpgrades {
            override fun observeUpgrade(): Flow<NetworkUpgrade?> = report
        },
        knownUpgrades = object : KnownUpgradeStore {
            override fun observe(): Flow<KnownUpgrade?> = flowOf(null)
            override suspend fun save(record: KnownUpgrade): Boolean = error("not used by this test")
        },
        network = Network.SEPOLIA,
    )

    private val daily = Account(
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )

    /** What the window (desktop) or the activity (Android) provides the [App] with. */
    private val host = object : LifecycleOwner, ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val viewModelStore = ViewModelStore()
    }

    private fun showApp() = rule.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides host, LocalViewModelStoreOwner provides host) {
            val controller = rememberNavController()
            if (navController.isEmpty()) navController += controller
            App(graph, controller)
        }
    }

    @Test
    fun theFloatingActionButtonAddsAnAccountFromItsSeedPhrase() {
        showApp()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).performTextInput(
            "one two three four five six seven eight nine ten eleven twelve",
        )
        // The first import sets the wallet password.
        rule.onNodeWithTag(MNEMONIC_PASSWORD_TAG).performTextInput("correct horse")
        rule.onNodeWithTag(MNEMONIC_REPEAT_TAG).performTextInput("correct horse")
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).performClick()
        rule.waitUntil { rule.onAllNodesWithText("Account 1").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Full control").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun aRequiredUpdateReplacesEveryScreenUntilTheLightClientVerifiesAgain() {
        showApp()
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertIsDisplayed()

        // Activated by peers' report, and the light client agrees it is not verifying.
        report.value = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, 4)
        rule.waitUntil { rule.onAllNodesWithTag(UPDATE_REQUIRED_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").assertDoesNotExist()
        // No tab bar either: the update screen is the only one.
        rule.onNodeWithText("Settings").assertDoesNotExist()

        status.value = LightClientStatus.Synced(headBlock = 100, headTimestamp = null)
        rule.waitUntil { rule.onAllNodesWithText("No accounts yet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(UPDATE_REQUIRED_TAG).assertDoesNotExist()
        rule.onNodeWithText("Network upgrade reported: update Serotine").assertIsDisplayed()
        rule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun theBackArrowReturnsHomeWithNothingAdded() {
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun theSettingsTabKeepsTheAssetsTabWhereItWas() {
        accounts.value = listOf(daily)
        showApp()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Receive").assertIsDisplayed()

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag(SETTINGS_VERSION_TAG).assertIsDisplayed()
        rule.onNodeWithText("The only network for now").assertIsDisplayed()

        rule.onNodeWithText("Assets").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
        // Selecting the tab it is on returns to the tab's start.
        rule.onNodeWithText("Assets").performClick()
        rule.onNodeWithTag(ASSETS_ACCOUNT_LIST_TAG).assertIsDisplayed()
    }

    @Test
    fun anAccountOpensItsDetailAndItsReceiveScreen() {
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithTag(ACCOUNT_ADDRESS_TAG).assertTextEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")
        rule.onNodeWithText("Receive").performClick()

        rule.onNodeWithTag(RECEIVE_ADDRESS_TAG).assertTextEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")
        rule.onNodeWithText("Only send Sepolia assets to this address").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun theLongPressMenuRenamesAnAccount() {
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Spending")
        rule.onNodeWithText("Save").performClick()

        rule.waitUntil { rule.onAllNodesWithText("Spending").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(RENAME_FIELD_TAG).assertDoesNotExist()
        assertEquals(listOf("Spending"), accounts.value.map { it.label })
    }

    @Test
    fun removingAnAccountFromItsDetailDeletesItsKeyAndReturnsToTheList() {
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithText("Only its recovery phrase brings this account back", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()

        rule.waitUntil { rule.onAllNodesWithText("No accounts yet").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(daily.address), deletedKeys)
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertDoesNotExist()
    }

    @Test
    fun aKeyThatCouldNotBeDeletedIsSaidBeforeTheDetailScreenCloses() {
        accounts.value = listOf(daily)
        failKeyDeletes = true
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithText("Remove").performClick()

        rule.waitUntil {
            rule.onAllNodesWithText("could not be deleted", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("OK").performClick()
        rule.waitUntil { rule.onAllNodesWithText("No accounts yet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertDoesNotExist()
    }

    @Test
    fun theBackKeyLeavesSettingsForTheAssetsScreenAndAScreenLeftNeverComesBack() {
        accounts.value = listOf(daily)
        showApp()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Settings").performClick()
        // The system's back, twice: from Settings to the account, from the account to the list.
        rule.runOnIdle { navController.single().popBackStack() }
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
        rule.runOnIdle { navController.single().popBackStack() }
        rule.onNodeWithTag(ASSETS_ACCOUNT_LIST_TAG).assertIsDisplayed()

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Assets").performClick()

        rule.onNodeWithTag(ASSETS_ACCOUNT_LIST_TAG).assertIsDisplayed()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertDoesNotExist()
    }

    @Test
    fun aTokenIsAddedFromSettingsAndTheScreenReturnsToTheList() {
        val eurc = "0x08210F9170F89Ab7658F0B5E3fF39b0E03C594D4"
        tokenMetadata.of(Address.of(eurc)).value = MetadataReading.Verified("EURC", "Euro Coin", 6, 9_412_118)
        showApp()

        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("0 built in · 0 added").performClick()
        rule.onNodeWithText("Add token").performClick()
        rule.onNodeWithTag(ADD_TOKEN_ADDRESS_TAG).performTextInput(eurc)
        rule.waitUntil { rule.onAllNodesWithText("Euro Coin · EURC").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Add token") and hasClickAction()).performClick()

        // Back on the list, which has the token; Settings counts it.
        rule.waitUntil { rule.onAllNodesWithTag(TOKENS_ROW_TAG).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText("Added: as its contract describes itself").assertIsDisplayed()
        rule.runOnIdle { navController.single().popBackStack() }
        rule.onNodeWithText("0 built in · 1 added").assertIsDisplayed()
    }

    @Test
    fun anAddressIsWatchedFromTheFloatingActionButton() {
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Watch address").performClick()
        rule.onNodeWithTag(WATCH_ADDRESS_TAG).performTextInput("0xd8da6bf26964af9d7eed9e03e53415d37aa96045")
        rule.onNodeWithTag(WATCH_NAME_TAG).performTextInput("Vitalik")
        rule.waitUntil { rule.onAllNodesWithText("Checked at block 9,412,118").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Add").performClick()

        rule.waitUntil { rule.onAllNodesWithText("Vitalik").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        assertEquals(listOf(AccountCustody.Watched), accounts.value.map { it.custody })
    }
}
