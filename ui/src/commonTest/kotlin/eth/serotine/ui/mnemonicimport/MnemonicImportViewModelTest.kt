package eth.serotine.ui.mnemonicimport

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.repository.AccountDerivation
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.Derivation
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Entering
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Imported
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicImportViewModelTest {

    private val twelveWords = "one two three four five six seven eight nine ten eleven twelve"
    private val address = Address.of("0x" + "12".repeat(20))

    private val derivation = FakeDerivation()
    private val repository = FakeAccountRepository()
    private val store = ViewModelStore()
    private val viewModel: MnemonicImportViewModel = ViewModelProvider.create(
        owner = object : ViewModelStoreOwner {
            override val viewModelStore = store
        },
        factory = viewModelFactory {
            initializer { MnemonicImportViewModel(ImportMnemonicAccount(derivation, repository)) }
        },
    )[MnemonicImportViewModel::class]

    @BeforeTest
    fun routeMainThroughTheTestScheduler() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun startsEmptyAndCountsTheWordsAsTheyAreTyped() {
        val start = assertIs<Entering>(viewModel.uiState.value)
        assertEquals("", start.phrase.text)
        assertEquals(0, start.wordCount)
        assertFalse(start.canImport)

        viewModel.onPhraseChange("one two three four five six seven eight nine ten eleven")
        assertEquals(11, entering().wordCount)
        assertFalse(entering().canImport)

        viewModel.onPhraseChange("$twelveWords ")
        assertEquals("$twelveWords ", entering().phrase.text, "the field shows back exactly what was typed")
        assertEquals(12, entering().wordCount)
        assertTrue(entering().canImport)
    }

    @Test
    fun aSuccessfulImportForgetsTheWordsAndReportsTheAccount() = runTest {
        val pending = CompletableDeferred<Derivation>()
        derivation.next = pending
        viewModel.onPhraseChange(twelveWords)

        viewModel.onImport()
        runCurrent()
        assertTrue(entering().importing)
        assertFalse(entering().canImport, "no second attempt while one runs")
        viewModel.onPhraseChange("changed while importing")
        assertEquals(twelveWords, entering().phrase.text, "the words are frozen while an attempt runs")

        pending.complete(Derivation.Derived(address))
        runCurrent()

        assertEquals(Imported("Account 1"), viewModel.uiState.value)
        assertEquals(twelveWords, derivation.received.single().text)
        assertEquals(listOf(Account(address, AccountKind.EOA, "Account 1")), repository.accounts.value)
    }

    @Test
    fun refusedWordsStayForCorrectionWithTheReason() = runTest {
        derivation.next = CompletableDeferred(Derivation.Invalid(MnemonicProblem.UnknownWord(position = 3)))
        viewModel.onPhraseChange(twelveWords)
        val typed = entering().phrase

        viewModel.onImport()
        runCurrent()

        assertSame(typed, entering().phrase)
        assertFalse(entering().importing)
        assertEquals("Word 3 is not in the BIP-39 English word list.", entering().error)

        viewModel.onPhraseChange("$twelveWords!")
        assertNull(entering().error, "editing clears the last refusal")
    }

    @Test
    fun anAccountAlreadyInTheWalletIsAnError() = runTest {
        repository.accounts.value = listOf(Account(address, AccountKind.EOA, "Daily"))
        derivation.next = CompletableDeferred(Derivation.Derived(address))
        viewModel.onPhraseChange(twelveWords)

        viewModel.onImport()
        runCurrent()

        assertEquals(
            "The first account of these words is already in the wallet, as “Daily”.",
            entering().error,
        )
    }

    @Test
    fun aFaultInTheKeyAdapterIsAnErrorNotAHang() = runTest {
        derivation.next = CompletableDeferred<Derivation>().apply {
            completeExceptionally(IllegalStateException("could not load the native library"))
        }
        viewModel.onPhraseChange(twelveWords)

        viewModel.onImport()
        runCurrent()

        assertFalse(entering().importing)
        assertEquals("The account could not be derived on this device.", entering().error)
        assertTrue(entering().canImport, "the user can try again")
    }

    @Test
    fun aDeviceTheKeyAdapterCannotRunOnIsAnError() = runTest {
        derivation.next = CompletableDeferred(Derivation.Unavailable)
        viewModel.onPhraseChange(twelveWords)

        viewModel.onImport()
        runCurrent()

        assertEquals("The account could not be derived on this device.", entering().error)
        assertEquals(twelveWords, entering().phrase.text)
    }

    @Test
    fun hidingTheScreenDropsTheWords() {
        viewModel.onPhraseChange(twelveWords)

        viewModel.onHidden()

        assertEquals("", entering().phrase.text)
    }

    @Test
    fun hidingTheScreenAbandonsARunningAttemptAndItsWords() = runTest {
        val pending = CompletableDeferred<Derivation>()
        derivation.next = pending
        viewModel.onPhraseChange(twelveWords)
        viewModel.onImport()
        runCurrent()

        viewModel.onHidden()
        pending.complete(Derivation.Invalid(MnemonicProblem.Checksum))
        runCurrent()

        assertEquals("", entering().phrase.text, "a late answer must not bring the words back")
        assertFalse(entering().importing)
        assertNull(entering().error)
    }

    @Test
    fun aFailedSaveIsSaidAndTheWordsStay() = runTest {
        repository.failSaves = true
        derivation.next = CompletableDeferred(Derivation.Derived(address))
        viewModel.onPhraseChange(twelveWords)

        viewModel.onImport()
        runCurrent()

        assertEquals("The account could not be saved on this device. Nothing was added.", entering().error)
        assertEquals(twelveWords, entering().phrase.text)
    }

    @Test
    fun aWordCountBip39DoesNotDefineIsNotSubmitted() = runTest {
        viewModel.onPhraseChange("only three words")

        viewModel.onImport()
        runCurrent()

        assertEquals(emptyList(), derivation.received)
    }

    @Test
    fun clearingTheViewModelDropsTheWords() {
        viewModel.onPhraseChange(twelveWords)

        store.clear()

        assertEquals("", entering().phrase.text)
    }

    private fun entering(): Entering = assertIs<Entering>(viewModel.uiState.value)

    private class FakeDerivation : AccountDerivation {
        var next = CompletableDeferred<Derivation>()
        val received = mutableListOf<Mnemonic>()

        override suspend fun deriveFirstAccount(mnemonic: Mnemonic): Derivation {
            received += mnemonic
            return next.await()
        }
    }

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())

        override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }

        var failSaves = false

        override suspend fun add(account: Account): AccountAdd {
            if (failSaves) return AccountAdd.NotSaved
            var added = false
            accounts.update { current ->
                added = current.none { it.address == account.address }
                if (added) current + account else current
            }
            return if (added) AccountAdd.Added else AccountAdd.AlreadyPresent
        }
    }
}
