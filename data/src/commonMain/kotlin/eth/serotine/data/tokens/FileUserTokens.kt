package eth.serotine.data.tokens

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.repository.UserTokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [UserTokens] kept in the file at [path] (a [VersionedJsonFile]): the tokens added by
 * address, as their contracts described themselves when added, and the hidden addresses.
 * A damaged file is set aside and the settings start empty, saying so
 * ([TokenSettings.Loaded.setAside]); one that cannot be read is [TokenSettings.Unreadable],
 * and nothing changes until it can be read.
 */
class FileUserTokens(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserTokens {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "token settings",
        formatVersion = FORMAT_VERSION,
        empty = TokenSettings.Loaded(emptyList(), emptySet()),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredSettings.serializer(), text)
            TokenSettings.Loaded(
                stored.added.map { it.toToken() }.distinctBy { it.address },
                stored.hidden.mapTo(LinkedHashSet()) { Address.of(it) },
            )
        },
        encode = { settings ->
            val stored = StoredSettings(
                FORMAT_VERSION,
                settings.added.map(::StoredToken),
                settings.hidden.map { it.hex },
            )
            JSON.encodeToString(StoredSettings.serializer(), stored)
        },
    )

    override fun observe(): Flow<TokenSettings> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> read.value.copy(setAside = read.damagedSetAside)
            is VersionedJsonFile.Read.Unreadable -> TokenSettings.Unreadable
        }
    }

    override suspend fun add(token: Token): TokenChange = file.change(TokenChange.NotSaved) { settings ->
        if (settings.added.any { it.address == token.address }) {
            Decision(TokenChange.AlreadyPresent)
        } else {
            Decision(TokenChange.Changed, settings.copy(added = settings.added + token))
        }
    }

    override suspend fun remove(address: Address): TokenChange = file.change(TokenChange.NotSaved) { settings ->
        if (settings.added.none { it.address == address }) {
            Decision(TokenChange.NotFound)
        } else {
            // A removed token is not hidden any more either: nothing of it is left behind.
            Decision(
                TokenChange.Changed,
                TokenSettings.Loaded(settings.added.filterNot { it.address == address }, settings.hidden - address),
            )
        }
    }

    override suspend fun setHidden(address: Address, hidden: Boolean): TokenChange =
        file.change(TokenChange.NotSaved) { settings ->
            if ((address in settings.hidden) == hidden) {
                Decision(TokenChange.AlreadyPresent)
            } else {
                Decision(
                    TokenChange.Changed,
                    settings.copy(hidden = if (hidden) settings.hidden + address else settings.hidden - address),
                )
            }
        }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredSettings(val version: Int, val added: List<StoredToken>, val hidden: List<String>)

    @Serializable
    private class StoredToken(val address: String, val symbol: String, val name: String, val decimals: Int) {
        constructor(token: Token) : this(token.address.hex, token.symbol, token.name, token.decimals)

        /** Throws on anything [Token] would not accept: the whole file is then damaged. */
        fun toToken(): Token = Token(Address.of(address), symbol, name, decimals, icon = null)
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
