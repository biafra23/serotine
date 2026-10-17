package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.Control

/** Everything the account detail screen renders (docs/wallet-ux.md, screen 4). */
sealed interface AccountDetailUiState {

    /** The account has not been read yet. */
    data object Loading : AccountDetailUiState

    /**
     * The account: its [label], its EIP-55 [address] and what the user can do with it
     * ([control]); its [total] in the display currency ("$3,250.00", or "Checking…") and what
     * the total leaves out ([totalNote]: "Testnet prices · 1 asset without a price"); its
     * [assets] — the native currency and every known token it holds — in the [sort] order;
     * [tokensNote], what is not known yet about the other tokens ("Checking 3 tokens"), or
     * null; [pricesNote], where the prices come from; and [message], how hiding a token
     * went, until it is said.
     */
    data class Ready(
        val label: String,
        val address: String,
        val kind: AccountKind,
        val control: Control,
        val total: String,
        val totalNote: String?,
        val assets: List<AssetRow> = emptyList(),
        val sort: AssetSort = AssetSort.VALUE,
        val tokensNote: String? = null,
        val pricesNote: String? = null,
        val message: String? = null,
    ) : AccountDetailUiState

    /** The account is not in the wallet (removed): the screen leaves. */
    data object Gone : AccountDetailUiState

    /** The stored list cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : AccountDetailUiState
}

/** How the asset rows are ordered (docs/wallet-ux.md, screen 4: Sort: Value | Name). */
enum class AssetSort {
    /** Highest value first; the assets without a price follow the priced ones, by name. */
    VALUE,

    /** By symbol. */
    NAME,
}

/**
 * One asset row (docs/wallet-ux.md, screen 4): its [symbol] and [name], the [amount] held —
 * verified when [verified], otherwise why there is none — the block it was verified at
 * ([verifiedAt], "block 11,932,144"; null when not verified or not named), its [value] in
 * the display currency, or why there is none ("No price"; null while there is no verified
 * amount to value), the bundled [icon] key (null: a monogram), a token's EIP-55 [contract]
 * (null for the native currency), which its long-press menu copies and hides, and
 * [warning], set when an added token's symbol is a built-in token's, which it is not.
 */
data class AssetRow(
    val symbol: String,
    val name: String,
    val amount: String,
    val icon: String?,
    val verified: Boolean,
    val verifiedAt: String? = null,
    val value: String? = null,
    val contract: String? = null,
    val warning: String? = null,
)
