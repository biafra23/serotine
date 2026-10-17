package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.flow.first

/**
 * Adds an address to the wallet to watch it: issue #9's watch-only account. Only what the
 * light client proves is an externally owned account — with or without an EIP-7702
 * delegation — is added, as an [AccountKind.EOA] held as [AccountCustody.Watched]; a
 * contract is refused (a Safe is added once Safes are detected), and so is an address whose
 * kind cannot be verified right now: the account's kind is chain data, shown verified or not
 * at all. An address already in the wallet, watched or with its key, is refused and named.
 * [label] is trimmed; empty, the account is named "Account N" as an import names it.
 */
class WatchAddress(
    private val accounts: AccountRepository,
    private val kinds: AddressKinds,
) {
    suspend operator fun invoke(address: Address, label: String): AddressWatch {
        val name = label.trim()
        if (name.length > RenameAccount.MAX_LABEL_LENGTH) return AddressWatch.NameTooLong
        val current = when (val list = accounts.observeAccounts().first()) {
            is AccountList.Unreadable -> return AddressWatch.NotSaved
            is AccountList.Loaded -> list.accounts
        }
        current.firstOrNull { it.address == address }?.let { return AddressWatch.AlreadyInWallet(it) }
        when (val reading = kinds.observeKind(address).first()) {
            KindReading.Syncing -> return AddressWatch.NotVerified(null)
            is KindReading.Unavailable -> return AddressWatch.NotVerified(reading.reason)
            is KindReading.Verified -> if (reading.kind == AddressKind.Contract) return AddressWatch.Contract
        }
        val account = Account(
            address,
            AccountKind.EOA,
            name.ifEmpty { nextFreeLabel(current) },
            AccountCustody.Watched,
        )
        return when (accounts.add(account)) {
            AccountAdd.Added -> AddressWatch.Added(account)
            AccountAdd.NotSaved -> AddressWatch.NotSaved
            // Added by another writer since the list was read: name what is there now.
            AccountAdd.AlreadyPresent -> when (val list = accounts.observeAccounts().first()) {
                is AccountList.Loaded -> list.accounts.firstOrNull { it.address == address }
                    ?.let { AddressWatch.AlreadyInWallet(it) } ?: AddressWatch.NotSaved
                is AccountList.Unreadable -> AddressWatch.NotSaved
            }
        }
    }
}

/** The outcome of [WatchAddress]. */
sealed interface AddressWatch {

    /** [account] is in the wallet, watched. */
    data class Added(val account: Account) : AddressWatch

    /** The address is in the wallet already, as [account]; nothing changed. */
    data class AlreadyInWallet(val account: Account) : AddressWatch

    /** The address holds a contract; nothing changed. */
    data object Contract : AddressWatch

    /**
     * The light client cannot prove the address's kind right now: still syncing ([reason]
     * null), or for the [reason] given. Nothing changed.
     */
    data class NotVerified(val reason: ReadUnavailable?) : AddressWatch

    /** The name is longer than [RenameAccount.MAX_LABEL_LENGTH]; nothing changed. */
    data object NameTooLong : AddressWatch

    /** The list could not be saved, or cannot be read; nothing changed. */
    data object NotSaved : AddressWatch
}
