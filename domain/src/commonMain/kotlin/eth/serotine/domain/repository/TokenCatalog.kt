package eth.serotine.domain.repository

import eth.serotine.domain.model.Token
import kotlinx.coroutines.flow.Flow

/**
 * The tokens the wallet knows on the network it works on: the built-in list (CLAUDE.md,
 * *Data sources*: pinned, each checked against its contract when listed), and, with the
 * Tokens settings, the ones the user adds. Nothing is fetched from a remote list.
 */
interface TokenCatalog {
    /** The known tokens, re-emitted when the set changes. Never fails. */
    fun observeTokens(): Flow<List<Token>>
}
