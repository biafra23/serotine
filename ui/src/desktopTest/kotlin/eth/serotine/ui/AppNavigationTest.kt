package eth.serotine.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.ui.accountdetail.ACCOUNT_BALANCE_TAG
import eth.serotine.ui.assets.ASSETS_ACCOUNT_LIST_TAG
import eth.serotine.ui.receive.RECEIVE_ADDRESS_TAG
import eth.serotine.ui.renameaccount.RENAME_FIELD_TAG
import eth.serotine.ui.settings.SETTINGS_VERSION_TAG
import kotlin.test.assertEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.ui.mnemonicimport.MNEMONIC_FIELD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_IMPORT_BUTTON_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_PASSWORD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_REPEAT_TAG
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

            override suspend fun remove(address: Address): AccountChange {
                accounts.update { list -> list.filterNot { it.address == address } }
                return AccountChange.Changed
            }
        },
        keys = object : KeyStore {
            override suspend fun describe() = KeyStoreDescription.Ready(passwordSet = false, KeyProtection.HARDWARE)

            override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
                KeyImport.Stored(Address.of("0x" + "ab".repeat(20)), KeyProtection.HARDWARE)

            override suspend fun delete(address: Address): KeyDeletion {
                deletedKeys += address
                return KeyDeletion.Deleted
            }
        },
        nativeBalances = object : NativeBalances {
            override fun observeBalance(address: Address): Flow<BalanceReading> = flowOf(BalanceReading.Syncing)
        },
        lightClient = object : LightClient {
            override fun observeStatus(): Flow<LightClientStatus> = flowOf(LightClientStatus.Starting)
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
            App(graph)
        }
    }

    @Test
    fun theFloatingActionButtonAddsAnAccountFromItsSeedPhrase() {
        showApp()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()

        rule.onNodeWithContentDescription("Add account").performClick()
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
    fun theBackArrowReturnsHomeWithNothingAdded() {
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
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
        rule.onNodeWithTag(ACCOUNT_BALANCE_TAG).assertIsDisplayed()
        // Selecting the tab it is on returns to the tab's start.
        rule.onNodeWithText("Assets").performClick()
        rule.onNodeWithTag(ASSETS_ACCOUNT_LIST_TAG).assertIsDisplayed()
    }

    @Test
    fun anAccountOpensItsDetailAndItsReceiveScreen() {
        accounts.value = listOf(daily)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("0x8f3A…91c2").assertIsDisplayed()
        rule.onNodeWithText("Receive").performClick()

        rule.onNodeWithTag(RECEIVE_ADDRESS_TAG).assertTextEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")
        rule.onNodeWithText("Only send Sepolia assets to this address").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(ACCOUNT_BALANCE_TAG).assertIsDisplayed()
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
        rule.onNodeWithTag(ACCOUNT_BALANCE_TAG).assertDoesNotExist()
    }
}
