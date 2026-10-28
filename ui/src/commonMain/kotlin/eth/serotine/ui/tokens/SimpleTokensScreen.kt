package eth.serotine.ui.tokens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Settings → Tokens in simple mode (docs/simple-ui.md): [TokensScreen] for the same [state], each
 * token's check said with no block. The tokens, their menus and Add token are as in Detailed view.
 */
@Composable
fun SimpleTokensScreen(
    state: TokensUiState,
    onBack: () -> Unit,
    onAddToken: () -> Unit,
    onCopy: (String) -> Unit,
    onSetHidden: (chainId: Long, address: String, hidden: Boolean) -> Unit,
    onRemove: (chainId: Long, address: String) -> Unit,
    modifier: Modifier = Modifier,
) = TokensScreen(
    state.copy(
        sections = state.sections.map { section ->
            section.copy(items = section.items.map { it.copy(status = it.simpleStatus ?: it.status) })
        },
    ),
    onBack = onBack,
    onAddToken = onAddToken,
    onCopy = onCopy,
    onSetHidden = onSetHidden,
    onRemove = onRemove,
    modifier = modifier,
)
