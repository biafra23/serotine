package eth.serotine.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.ui.mnemonicimport.MNEMONIC_FIELD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_IMPORT_BUTTON_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_PASSWORD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_REPEAT_TAG
import eth.serotine.ui.update.UPDATE_REQUIRED_TAG
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

/** The shared [App] end to end, over fake ports: Home → add account → back on Home. */
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
    private val report = MutableStateFlow<NetworkUpgrade?>(null)
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val graph = AppGraph(
        accounts = object : AccountRepository {
            override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }

            override suspend fun add(account: Account): AccountAdd {
                accounts.update { it + account }
                return AccountAdd.Added
            }
        },
        keys = object : KeyStore {
            override suspend fun describe() = KeyStoreDescription.Ready(passwordSet = false, KeyProtection.HARDWARE)

            override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
                KeyImport.Stored(Address.of("0x" + "ab".repeat(20)), KeyProtection.HARDWARE)
        },
        nativeBalances = object : NativeBalances {
            override fun observeBalance(address: Address): Flow<BalanceReading> = flowOf(BalanceReading.Syncing)
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

        rule.onNodeWithText("0xabab…abab · Account").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun aRequiredUpdateReplacesEveryScreenUntilTheLightClientVerifiesAgain() {
        showApp()
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertIsDisplayed()

        // Activated by peers' report, and the light client agrees it is not verifying.
        report.value = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, 4)
        rule.waitUntil { rule.onAllNodesWithTag(UPDATE_REQUIRED_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").assertDoesNotExist()

        status.value = LightClientStatus.Synced(headBlock = 100, headTimestamp = null)
        rule.waitUntil { rule.onAllNodesWithText("No accounts yet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(UPDATE_REQUIRED_TAG).assertDoesNotExist()
        rule.onNodeWithText("Network upgrade reported: update Serotine").assertIsDisplayed()
    }

    @Test
    fun theBackArrowReturnsHomeWithNothingAdded() {
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }
}
