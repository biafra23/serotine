package eth.serotine.domain.model

/** What kind of account an [Address] is, which decides how Serotine signs for it. */
enum class AccountKind {
    /** An externally owned account: a key pair signs its transactions directly. */
    EOA,

    /** A Safe{Wallet} smart-contract account: its owners sign Safe transactions for it. */
    SAFE,
}

/**
 * How the wallet holds an [Account], which decides what it can do with it: one variant per
 * way of holding one, never a flag (docs/architecture.md, *SOLID*). A [Watched] account has
 * no signing path at all.
 */
sealed interface AccountCustody {

    /** Only the address is known: the wallet shows what the chain says about it and signs nothing. */
    data object Watched : AccountCustody

    /** Its key is in this device's key store, sealed in the envelope of the recovery phrase it came from. */
    data object InKeyStore : AccountCustody
}

/**
 * An account Serotine manages, as the rest of the app sees it. The immutable snapshot a
 * repository emits; the key material behind an [AccountCustody.InKeyStore] account is
 * never part of it.
 */
data class Account(
    val address: Address,
    val kind: AccountKind,
    /** The user-facing name, never blank. */
    val label: String,
    val custody: AccountCustody,
) {
    init {
        require(label.isNotBlank()) { "an account needs a label" }
    }
}
