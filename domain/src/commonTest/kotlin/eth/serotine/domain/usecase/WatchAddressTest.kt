package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchAddressTest {

    private val address = Address.of("0x" + "ab".repeat(20))
    private val daily = Account(Address.of("0x" + "11".repeat(20)), AccountKind.EOA, "Daily", AccountCustody.InKeyStore)
    private val repository = ListAccountRepository(daily)
    private var reading: KindReading = KindReading.Verified(AddressKind.Eoa, 100)
    private val kinds = object : AddressKinds {
        override fun observeKind(address: Address): Flow<KindReading> = flowOf(reading)
    }
    private val watch = WatchAddress(repository, kinds)

    @Test
    fun aVerifiedAccountIsAddedWatchedUnderTheGivenName() = runTest {
        val expected = Account(address, AccountKind.EOA, "Savings", AccountCustody.Watched)

        assertEquals(AddressWatch.Added(expected), watch(address, "  Savings "))
        assertEquals(listOf(daily, expected), repository.accounts.value)
    }

    @Test
    fun withoutANameItIsNamedAsAnImportWouldBe() = runTest {
        val added = watch(address, "  ") as AddressWatch.Added

        assertEquals("Account 2", added.account.label)
    }

    @Test
    fun withoutANameItsNameFollowsTheEnsNameItWasResolvedFrom() = runTest {
        val added = watch(address, "  ", ensName = "savings.eth") as AddressWatch.Added

        assertEquals(
            Account(address, AccountKind.EOA, "savings.eth", AccountCustody.Watched, ensName = "savings.eth"),
            added.account,
        )
    }

    @Test
    fun aNameGivenIsTheUsersEvenWithAnEnsName() = runTest {
        val added = watch(address, "Savings", ensName = "savings.eth") as AddressWatch.Added

        assertEquals(Account(address, AccountKind.EOA, "Savings", AccountCustody.Watched), added.account)
    }

    @Test
    fun anEnsNameTooLongForARowIsNotFollowed() = runTest {
        val long = "a".repeat(RenameAccount.MAX_LABEL_LENGTH - 3) + ".eth"

        val added = watch(address, "", ensName = long) as AddressWatch.Added

        assertEquals(Account(address, AccountKind.EOA, "Account 2", AccountCustody.Watched), added.account)
    }

    @Test
    fun aDelegatedAccountIsStillAnAccount() = runTest {
        reading = KindReading.Verified(AddressKind.DelegatedEoa(daily.address), 100)

        assertEquals(AccountCustody.Watched, (watch(address, "") as AddressWatch.Added).account.custody)
    }

    @Test
    fun aVerifiedSafeIsAddedAsASafe() = runTest {
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address), 1, 0, emptyList(), guard = null)
        reading = KindReading.Verified(AddressKind.Safe(state), 100)

        val added = watch(address, "Treasury") as AddressWatch.Added
        assertEquals(Account(address, AccountKind.SAFE, "Treasury", AccountCustody.Watched), added.account)
    }

    @Test
    fun aContractIsRefused() = runTest {
        reading = KindReading.Verified(AddressKind.Contract, 100)

        assertEquals(AddressWatch.Contract, watch(address, "Some contract"))
        assertEquals(listOf(daily), repository.accounts.value)
    }

    @Test
    fun anUnverifiedKindIsRefused() = runTest {
        reading = KindReading.Syncing
        assertEquals(AddressWatch.NotVerified(null), watch(address, ""))

        reading = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(AddressWatch.NotVerified(ReadUnavailable.UNPROVEN), watch(address, ""))
        assertEquals(listOf(daily), repository.accounts.value)
    }

    @Test
    fun anAddressInTheWalletAlreadyIsNamedNotAddedAgain() = runTest {
        assertEquals(AddressWatch.AlreadyInWallet(daily), watch(daily.address, "Again"))
    }

    @Test
    fun anAddressAddedByAnotherWriterMeanwhileIsNamed() = runTest {
        val other = Account(address, AccountKind.EOA, "Theirs", AccountCustody.InKeyStore)
        val racing = object : AddressKinds {
            // The other writer adds the address while its kind is being proven.
            override fun observeKind(address: Address): Flow<KindReading> = flow {
                repository.accounts.value = repository.accounts.value + other
                emit(reading)
            }
        }

        assertEquals(AddressWatch.AlreadyInWallet(other), WatchAddress(repository, racing)(address, "Mine"))
    }

    @Test
    fun anUnreadableListAddsNothing() = runTest {
        repository.unreadable = true

        assertEquals(AddressWatch.NotSaved, watch(address, "Savings"))
    }

    @Test
    fun aNameTooLongOrAListThatCannotBeSavedChangesNothing() = runTest {
        assertEquals(AddressWatch.NameTooLong, watch(address, "x".repeat(RenameAccount.MAX_LABEL_LENGTH + 1)))

        repository.failSaves = true
        assertEquals(AddressWatch.NotSaved, watch(address, "Savings"))
        assertEquals(listOf(daily), repository.accounts.value)
    }
}
