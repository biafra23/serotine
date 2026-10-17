package eth.serotine.ui.tokens

/**
 * Everything Settings → Tokens renders (docs/wallet-ux.md, screens 18 and 20): every token the
 * wallet knows, and [notice], why the settings cannot be changed right now, or what the last
 * change could not do.
 */
data class TokensUiState(val items: List<TokenItem> = emptyList(), val notice: String? = null)

/**
 * One known token: its [symbol], [name], EIP-55 contract [address] and icon key ([icon]; null:
 * a monogram); [builtIn] or added by the user; [status], what its own check says, which is a
 * [problem] when the entry is not shown for it; [hidden] by the user; and [warning], set when
 * an added token's symbol is a built-in token's.
 */
data class TokenItem(
    val symbol: String,
    val name: String,
    val address: String,
    val icon: String?,
    val builtIn: Boolean,
    val status: String,
    val problem: Boolean,
    val hidden: Boolean,
    val warning: String? = null,
)
