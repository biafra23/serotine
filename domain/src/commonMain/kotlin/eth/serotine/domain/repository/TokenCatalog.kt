package eth.serotine.domain.repository

import eth.serotine.domain.model.Token
import kotlinx.coroutines.flow.Flow

/**
 * The built-in token list of the network the wallet works on (CLAUDE.md, *Data sources*:
 * pinned, each checked against its contract when listed). Nothing is fetched from a remote
 * list. The tokens the user adds and hides are [UserTokens]'.
 */
interface TokenCatalog {
    /** The built-in tokens, re-emitted when the set changes. Never fails. */
    fun observeTokens(): Flow<List<Token>>
}
