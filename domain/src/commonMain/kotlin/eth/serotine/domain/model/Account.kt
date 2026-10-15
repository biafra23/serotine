package eth.serotine.domain.model

/** What kind of account an [Address] is, which decides how Serotine signs for it. */
enum class AccountKind {
    /** An externally owned account: a key pair signs its transactions directly. */
    EOA,

    /** A Safe{Wallet} smart-contract account: its owners sign Safe transactions for it. */
    SAFE,
}

/**
 * An account Serotine manages, as the rest of the app sees it. The immutable snapshot a
 * repository emits; the key material behind an [AccountKind.EOA] is never part of it.
 */
data class Account(
    val address: Address,
    val kind: AccountKind,
    /** The user-facing name, never blank. */
    val label: String,
) {
    init {
        require(label.isNotBlank()) { "an account needs a label" }
    }
}
