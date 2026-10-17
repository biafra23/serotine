package eth.serotine.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.ui.accountdetail.ACCOUNT_ADDRESS_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_QUEUE_ROW_TAG
import eth.serotine.ui.accountdetail.ACCOUNT_TOTAL_TAG
import eth.serotine.ui.addtoken.ADD_TOKEN_ADDRESS_TAG
import eth.serotine.ui.assets.ASSETS_ACCOUNT_LIST_TAG
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.createsafe.CREATE_SAFE_CONTINUE_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_DEPLOY_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_NAME_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_PASSWORD_TAG
import eth.serotine.ui.createsafe.CREATE_SAFE_THRESHOLD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_FIELD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_IMPORT_BUTTON_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_PASSWORD_TAG
import eth.serotine.ui.mnemonicimport.MNEMONIC_REPEAT_TAG
import eth.serotine.ui.receive.RECEIVE_ADDRESS_TAG
import eth.serotine.ui.renameaccount.RENAME_FIELD_TAG
import eth.serotine.ui.safeimport.IMPORT_REQUEST_BUTTON_TAG
import eth.serotine.ui.safeimport.IMPORT_REQUEST_FIELD_TAG
import eth.serotine.ui.safepropose.PROPOSE_AMOUNT_REVIEWED_TAG
import eth.serotine.ui.safepropose.PROPOSE_PASSWORD_TAG
import eth.serotine.ui.safepropose.PROPOSE_SIGN_TAG
import eth.serotine.ui.safetx.SAFE_TX_HASH_TAG
import eth.serotine.ui.safetx.SAFE_TX_PASSWORD_TAG
import eth.serotine.ui.components.SAFE_TX_QR_TAG
import eth.serotine.ui.components.SAFE_TX_REQUEST_TAG
import eth.serotine.ui.safetx.SAFE_TX_SHARE_TAG
import eth.serotine.ui.safetx.SAFE_TX_SIGN_TAG
import eth.serotine.ui.send.SEND_AMOUNT_REVIEWED_TAG
import eth.serotine.ui.components.SEND_AMOUNT_TAG
import eth.serotine.ui.send.SEND_PASSWORD_TAG
import eth.serotine.ui.components.SEND_RECIPIENT_TAG
import eth.serotine.ui.components.SEND_REVIEW_TAG
import eth.serotine.ui.send.SEND_SIGN_TAG
import eth.serotine.ui.settings.SETTINGS_VERSION_TAG
import eth.serotine.ui.tokens.TOKENS_ROW_TAG
import eth.serotine.ui.transferstatus.TRANSFER_HASH_TAG
import eth.serotine.ui.transferstatus.TRANSFER_SAFE_TAG
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

/**
 * The shared [App] end to end, over fake ports: the tabs, the account screens and their dialogs,
 * and the update screen that replaces them all while a network upgrade leaves this version behind.
 */
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
    private val nativeBalance = MutableStateFlow<BalanceReading>(BalanceReading.Syncing)
    private val recovery = FakeRecovery()
    private val signer = FakeSigner(recovery)
    private val safeTxQueue = MemorySafeTxQueue()
    private val codec = FakeCodec()

    /** Treasury's Safe: Daily and Carol own it, both must sign, and it is at nonce 4. */
    private val treasuryAddress = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
    private val carol = Address.of("0x" + "33".repeat(20))
    private val broadcaster = FakeBroadcaster()
    private val sentTransfers = MemorySentTransfers()
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

            override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
                BiometricChange.NotOnThisDevice

            override suspend fun disableBiometrics(): BiometricChange = BiometricChange.NotOnThisDevice

            override suspend fun describe() = KeyStoreDescription.Ready(passwordSet = false, KeyProtection.HARDWARE)

            override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
                KeyImport.Stored(Address.of("0x" + "ab".repeat(20)), KeyProtection.HARDWARE)

            override suspend fun delete(address: Address): KeyDeletion {
                deletedKeys += address
                return if (failKeyDeletes) KeyDeletion.Failed else KeyDeletion.Deleted
            }
        },
        nativeBalances = object : NativeBalances {
            override fun observeBalance(address: Address): Flow<BalanceReading> = nativeBalance
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
            override fun observeKind(address: Address): Flow<KindReading> = if (address == treasuryAddress) {
                val state = SafeState("1.4.1", l2 = false, listOf(daily.address, carol), 2, 4, emptyList(), null)
                flowOf(KindReading.Verified(AddressKind.Safe(state), 9_412_118))
            } else {
                flowOf(KindReading.Verified(AddressKind.Eoa, 9_412_118))
            }
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
        transactionInputs = FactoryInputs(),
        broadcaster = broadcaster,
        receipts = FakeReceipts(),
        names = FakeNames(),
        signer = signer,
        sentTransfers = sentTransfers,
        safeTxQueue = safeTxQueue,
        signingRequests = codec,
        signatureRecovery = recovery,
        safeApprovals = FakeApprovals(),
        safeReleases = FakeReleases(),
        network = Network.SEPOLIA,
    )

    private val daily = Account(
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )

    private val savings = Account(
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Savings",
        AccountCustody.Watched,
    )

    private val treasury = Account(treasuryAddress, AccountKind.SAFE, "Treasury", AccountCustody.Watched)

    /** 0.1 ETH from Treasury to Savings at its nonce, 4. */
    private val queued = SigningRequest.of(
        Network.SEPOLIA,
        treasuryAddress,
        SafeTx.call(savings.address, UInt256.of(100_000_000_000_000_000), ByteArray(0), 4),
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
    fun aRequiredUpdateFromATabLeavesNothingUnderItAndRecoversToTheAssetsStart() {
        accounts.value = listOf(daily)
        showApp()
        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag(SETTINGS_VERSION_TAG).assertIsDisplayed()

        report.value = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, 4)
        rule.waitUntil { rule.onAllNodesWithTag(UPDATE_REQUIRED_TAG).fetchSemanticsNodes().isNotEmpty() }
        // The account and Settings are gone, and both tabs' stacks with them: back leaves the app.
        assertEquals(listOf("update-required"), screens())

        status.value = LightClientStatus.Synced(headBlock = 100, headTimestamp = null)
        rule.waitUntil { rule.onAllNodesWithTag(ASSETS_ACCOUNT_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("assets"), screens(), "the Assets start, with neither the account nor Settings under it")
    }

    /** The screens on the back stack, bottom first, without the graphs that hold them. */
    private fun screens(): List<String?> = rule.runOnIdle {
        navController.single().currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route }
    }

    @Test
    fun theBackArrowReturnsToAssetsWithNothingAdded() {
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
    fun settingsOpensSecurityAndItsBackArrowReturns() {
        showApp()
        rule.onNodeWithText("Settings").performClick()

        rule.onNodeWithText("Wallet password · Biometric unlock").performClick()
        rule.onNodeWithText("Biometric unlock").assertIsDisplayed()
        // The fake key store is the desktop's: no biometric path, and it says so.
        rule.onNodeWithText("Not on this device yet", substring = true).assertIsDisplayed()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(SETTINGS_VERSION_TAG).assertIsDisplayed()
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
    fun anAccountSendsFromItsDetailThroughTheReviewToWhereTheTransferStands() {
        accounts.value = listOf(daily, savings)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp()

        rule.onNodeWithText("Daily").performClick()
        rule.onNodeWithText("Send").performClick()
        // Over the bar, as every flow that ends in a transaction.
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Savings · 0xfB69…d359").performClick()
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performTextInput("0.1")
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SEND_AMOUNT_REVIEWED_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(SEND_AMOUNT_REVIEWED_TAG).assertTextEquals("0.1 ETH")
        rule.onNodeWithTag(SEND_RECIPIENT_TAG).assertTextEquals("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359")
        rule.onNodeWithTag(SEND_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(SEND_SIGN_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(TRANSFER_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Waiting for a block to include it").assertIsDisplayed()
        val hash = sentTransfers.transfers.value.single().hash
        rule.onNodeWithTag(TRANSFER_HASH_TAG).performScrollTo().assertTextEquals(hash)
        assertEquals(listOf("correct horse"), signer.passwords)
        assertEquals(1, broadcaster.sent.size)
        // The signed review is gone from the back stack: Done returns to the account.
        assertEquals(listOf("assets", "account/{address}", "transfer/{hash}"), screens())
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun aSafesQueuedTransactionOpensFromItsQueueAndIsSharedAsARequest() {
        accounts.value = listOf(daily, treasury)
        safeTxQueue.requests.value = listOf(queued)
        showApp()

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Queue (1)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Queue (1)").performClick()
        rule.onAllNodesWithTag(ACCOUNT_QUEUE_ROW_TAG).assertCountEquals(1)
        rule.onNodeWithText("Send 0.1 ETH").performClick()

        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SAFE_TX_HASH_TAG).performScrollTo().assertTextEquals("0x" + queued.hash.hex)
        // A Safe transaction covers the bar, as every flow that ends in a signature or a transaction.
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithTag(SAFE_TX_SHARE_TAG).performScrollTo().performClick()

        rule.onNodeWithTag(SAFE_TX_QR_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SAFE_TX_REQUEST_TAG).performScrollTo().assertTextEquals(codec.encode(queued))
        assertEquals(listOf("assets", "account/{address}", "safe/{address}/tx/{hash}"), screens())
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun sendFromASafeProposesATransferThatAnOwnerHeldHereSignsAndQueues() {
        accounts.value = listOf(daily, treasury)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp()

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Co-signer").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Send").performClick()
        rule.onNodeWithText("Send from a Safe").assertIsDisplayed()
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Daily · 0x8f3A…91c2").performScrollTo().performClick()
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performScrollTo().performTextInput("0.1")
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(PROPOSE_AMOUNT_REVIEWED_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(PROPOSE_AMOUNT_REVIEWED_TAG).assertTextEquals("0.1 ETH")
        rule.onNodeWithText("Safe nonce 4").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(PROPOSE_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(PROPOSE_SIGN_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        val proposed = safeTxQueue.requests.value.single()
        assertEquals(listOf(daily.address), proposed.signers)
        rule.onNodeWithTag(SAFE_TX_HASH_TAG).performScrollTo().assertTextEquals("0x" + proposed.hash.hex)
        assertEquals(listOf("correct horse"), signer.passwords)
        // The proposal is gone from the back stack: back from the transaction returns to the Safe.
        assertEquals(listOf("assets", "account/{address}", "safe/{address}/tx/{hash}"), screens())
    }

    @Test
    fun aRequestImportedFromAnotherDeviceOpensItsTransaction() {
        accounts.value = listOf(daily, treasury)
        showApp()
        val signature = SafeSignature.Ecdsa(carol, recovery.sign(carol, queued.hash))
        val text = codec.encode(queued.withSignatures(listOf(signature)))

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Queue (0)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Queue (0)").performClick()
        rule.onNodeWithText("Import a request").performClick()
        rule.onNodeWithTag(IMPORT_REQUEST_FIELD_TAG).performTextInput(text)
        rule.onNodeWithTag(IMPORT_REQUEST_BUTTON_TAG).performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(SAFE_TX_HASH_TAG).performScrollTo().assertTextEquals("0x" + queued.hash.hex)
        assertEquals(listOf(carol), safeTxQueue.requests.value.single().signers)
        assertEquals(listOf("assets", "account/{address}", "safe/{address}/tx/{hash}"), screens())
    }

    @Test
    fun anApprovalOnChainIsSentFromTheTransactionToWhereItStands() {
        accounts.value = listOf(daily, treasury)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        safeTxQueue.requests.value = listOf(queued)
        showApp()

        rule.onNodeWithText("Treasury").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Queue (1)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Queue (1)").performClick()
        rule.onNodeWithText("Send 0.1 ETH").performClick()
        rule.waitUntil { rule.onAllNodesWithText("Approve on chain").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Approve on chain").performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(SAFE_TX_PASSWORD_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SAFE_TX_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(SAFE_TX_SIGN_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(TRANSFER_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Approval of a Safe transaction").assertIsDisplayed()
        val approval = sentTransfers.transfers.value.single()
        assertEquals(SentAction.SafeApproval(queued), approval.action)
        rule.onNodeWithTag(TRANSFER_HASH_TAG).performScrollTo().assertTextEquals(approval.hash)
        // The signed review is gone from the back stack: Done returns to the Safe.
        assertEquals(listOf("assets", "account/{address}", "transfer/{hash}"), screens())
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun aWatchOnlyAccountOffersNoSend() {
        accounts.value = listOf(savings)
        showApp()

        rule.onNodeWithText("Savings").performClick()
        rule.onNodeWithText("Receive").assertIsDisplayed()
        rule.onNodeWithText("Send").assertDoesNotExist()
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

    @Test
    fun aSafeIsCreatedFromTheFloatingActionButtonAndItsDeploymentFollowed() {
        accounts.value = listOf(daily, savings)
        nativeBalance.value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Create Safe").performClick()
        // Over the bar, as every flow that ends in a transaction.
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithText("Create Safe 1/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_NAME_TAG).performTextInput("Family vault")
        rule.onNodeWithText("Savings").performClick()
        rule.onNodeWithTag(CREATE_SAFE_CONTINUE_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Create Safe 2/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_THRESHOLD_TAG).assertTextEquals("1 of 2")
        rule.onNodeWithTag(CREATE_SAFE_CONTINUE_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithText("Verified simulation").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Create Safe 3/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_PASSWORD_TAG).performScrollTo().performTextInput("correct horse")
        rule.onNodeWithTag(CREATE_SAFE_DEPLOY_TAG).performScrollTo().performClick()
        rule.waitUntil { rule.onAllNodesWithTag(TRANSFER_HASH_TAG).fetchSemanticsNodes().isNotEmpty() }

        val deployment = sentTransfers.transfers.value.single()
        val action = deployment.action as SentAction.SafeDeployment
        assertEquals(listOf(daily.address, savings.address), action.owners)
        assertEquals("Family vault", action.label)
        rule.onNodeWithText("Deployment of a Safe").assertIsDisplayed()
        rule.onNodeWithTag(TRANSFER_SAFE_TAG).assertTextEquals(Eip55.format(action.safe))
        assertEquals(listOf("correct horse"), signer.passwords)
        // The signed review is gone from the back stack: Done returns to Assets.
        assertEquals(listOf("assets", "transfer/{hash}"), screens())
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithTag(ASSETS_ACCOUNT_LIST_TAG).assertIsDisplayed()
    }

    @Test
    fun withoutAKeyHereNoSafeIsOffered() {
        accounts.value = listOf(savings)
        showApp()

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Watch address").assertIsDisplayed()
        rule.onNodeWithText("Create Safe").assertDoesNotExist()
    }
}
