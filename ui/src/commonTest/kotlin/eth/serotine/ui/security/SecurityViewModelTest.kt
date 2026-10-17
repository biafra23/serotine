package eth.serotine.ui.security

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.DisableBiometricUnlock
import eth.serotine.domain.usecase.EnableBiometricUnlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {

    private val keys = FakeKeyStore()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SecurityViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        return SecurityViewModel(DescribeKeyStore(keys), EnableBiometricUnlock(keys), DisableBiometricUnlock(keys))
    }

    private val SecurityViewModel.ready get() = assertIs<SecurityUiState.Ready>(uiState.value)

    @Test
    fun aWalletWithAPasswordCanTurnBiometricUnlockOnWithIt() = runTest {
        val viewModel = viewModel()
        runCurrent()
        assertEquals("Set", viewModel.ready.password)
        assertEquals(
            BiometricRow("Off: signing asks for the wallet password.", on = false, canChange = true),
            viewModel.ready.biometrics,
        )

        viewModel.onBiometricsChange(true)
        viewModel.onPasswordChange("correct horse battery")
        keys.description = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, BiometricUnlock.On)
        viewModel.onConfirm()
        keys.enabling.complete(BiometricChange.Enabled)
        runCurrent()

        assertEquals("correct horse battery", keys.passwords.single().text)
        assertNull(viewModel.ready.enabling, "the password is gone")
        assertTrue(viewModel.ready.biometrics.on)
        assertEquals("Biometric unlock is on.", viewModel.ready.message)
    }

    @Test
    fun aWrongPasswordIsSaidAndTheFieldStays() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onBiometricsChange(true)
        viewModel.onPasswordChange("mistyped password")
        viewModel.onConfirm()
        keys.enabling.complete(BiometricChange.WrongPassword)
        runCurrent()

        val prompt = assertIs<PasswordPrompt>(viewModel.ready.enabling)
        assertEquals("That is not the wallet password.", prompt.error)
        assertEquals("", prompt.password.text, "nothing typed is kept for another try")
    }

    @Test
    fun turningItOffNeedsNoPassword() = runTest {
        keys.description = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, BiometricUnlock.On)
        val viewModel = viewModel()
        runCurrent()

        keys.description = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, BiometricUnlock.Off)
        viewModel.onBiometricsChange(false)
        runCurrent()

        assertEquals(1, keys.disabled)
        assertEquals("Biometric unlock is off, and its key is deleted from this device.", viewModel.ready.message)
        assertEquals(false, viewModel.ready.biometrics.on)
    }

    @Test
    fun leavingTheScreenDropsTheTypedPassword() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onBiometricsChange(true)
        viewModel.onPasswordChange("correct horse battery")

        viewModel.onHidden()

        assertNull(viewModel.ready.enabling)
        assertTrue("correct horse battery" !in viewModel.uiState.value.toString())
    }

    @Test
    fun whatTheDeviceCannotOfferIsSaidAndTheSwitchStays() = runTest {
        keys.description = KeyStoreDescription.Ready(
            true,
            KeyProtection.HARDWARE,
            BiometricUnlock.Unavailable(BiometricUnavailable.WEAK_ONLY),
        )
        val viewModel = viewModel()
        runCurrent()

        assertEquals(false, viewModel.ready.biometrics.canChange)
        assertTrue(viewModel.ready.biometrics.status.contains("not strong enough"))
        viewModel.onBiometricsChange(true)
        assertNull(viewModel.ready.enabling)
    }

    @Test
    fun withNoKeyStoredThereIsNothingToUnlock() = runTest {
        keys.description = KeyStoreDescription.Ready(false, KeyProtection.HARDWARE, BiometricUnlock.Off)
        val viewModel = viewModel()
        runCurrent()

        assertEquals("Not set yet: the first key you store sets it", viewModel.ready.password)
        assertNull(viewModel.ready.protection)
        assertEquals(false, viewModel.ready.biometrics.canChange)
    }

    @Test
    fun theDesktopSaysItHasNoBiometricUnlockYet() = runTest {
        keys.description = KeyStoreDescription.Ready(true, KeyProtection.PASSWORD_ONLY)
        val viewModel = viewModel()
        runCurrent()

        assertTrue(viewModel.ready.biometrics.status.startsWith("Not on this device yet"))
        assertEquals(false, viewModel.ready.biometrics.canChange)
    }

    @Test
    fun aKeyStoreThatDoesNotWorkIsSaid() = runTest {
        keys.description = KeyStoreDescription.Unavailable
        val viewModel = viewModel()
        runCurrent()

        assertEquals(SecurityUiState.Unavailable, viewModel.uiState.value)
    }

    private class FakeKeyStore : KeyStore {
        var description: KeyStoreDescription =
            KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, BiometricUnlock.Off)
        val enabling = CompletableDeferred<BiometricChange>()
        val passwords = mutableListOf<WalletPassword>()
        var disabled = 0

        override suspend fun describe(): KeyStoreDescription = description

        override suspend fun enableBiometrics(password: WalletPassword): BiometricChange {
            passwords += password
            return enabling.await()
        }

        override suspend fun disableBiometrics(): BiometricChange {
            disabled++
            return BiometricChange.Disabled
        }

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
            error("not used by these tests")

        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }
}
