package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.Control

/** Everything the account detail screen renders (docs/wallet-ux.md, screen 4). */
sealed interface AccountDetailUiState {

    /** The account has not been read yet. */
    data object Loading : AccountDetailUiState

    /**
     * The account: its [label], its EIP-55 [address], what the user can do with it
     * ([control]) and its native [balance], verified at the block [balanceNote] names when
     * [balanceVerified], or why there is none; its [assets] — the native currency, then every
     * known token it holds — and [tokensNote], what is not known yet about the other tokens
     * ("Checking 3 tokens"), or null.
     */
    data class Ready(
        val label: String,
        val address: String,
        val kind: AccountKind,
        val control: Control,
        val balance: String,
        val balanceVerified: Boolean,
        val balanceNote: String?,
        val assets: List<AssetRow> = emptyList(),
        val tokensNote: String? = null,
    ) : AccountDetailUiState

    /** The account is not in the wallet (removed): the screen leaves. */
    data object Gone : AccountDetailUiState

    /** The stored list cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : AccountDetailUiState
}

/**
 * One asset row (docs/wallet-ux.md, screen 4): its [symbol] and [name], the [amount] held —
 * verified when [verified], otherwise why there is none — the block it was verified at
 * ([verifiedAt], "block 11,932,144"; null when not verified or not named), the bundled
 * [icon] key (null: a monogram), and a token's EIP-55 [contract] (null for the native
 * currency), which its long-press menu copies and hides.
 */
data class AssetRow(
    val symbol: String,
    val name: String,
    val amount: String,
    val icon: String?,
    val verified: Boolean,
    val verifiedAt: String? = null,
    val contract: String? = null,
)
