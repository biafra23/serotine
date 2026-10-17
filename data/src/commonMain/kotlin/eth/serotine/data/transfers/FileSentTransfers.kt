package eth.serotine.data.transfers

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SentTransfers] kept in the file at [path] (a [VersionedJsonFile]): the signed transfers, each
 * with its signed bytes as hex, which are public once broadcast. A damaged file is set aside
 * and the record starts empty; one that cannot be read is [SentTransferList.Unreadable] (a
 * quote then goes by the engine's nonce alone).
 */
class FileSentTransfers(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SentTransfers {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "sent transfers",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<SentTransfer>(),
        decode = { _, text -> JSON.decodeFromString(StoredFile.serializer(), text).transfers.map { it.toTransfer() } },
        encode = { transfers ->
            JSON.encodeToString(StoredFile.serializer(), StoredFile(FORMAT_VERSION, transfers.map(::stored)))
        },
    )

    override fun observe(): Flow<SentTransferList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> SentTransferList.Loaded(read.value)
            is VersionedJsonFile.Read.Unreadable -> SentTransferList.Unreadable
        }
    }

    override suspend fun record(transfer: SentTransfer): Boolean = file.change(notSaved = false) { transfers ->
        // One record per hash: signing the same quote twice is one transaction.
        Decision(true, next = transfers.filterNot { it.hash == transfer.hash } + transfer)
    }

    override suspend fun forgetMined(account: Address, minedCount: Long): Boolean =
        file.change(notSaved = false) { transfers ->
            val kept = transfers.filterNot { it.account == account && it.nonce < minedCount }
            if (kept.size == transfers.size) Decision(true) else Decision(true, next = kept)
        }

    override suspend fun forget(hash: String): Boolean = file.change(notSaved = false) { transfers ->
        val kept = transfers.filterNot { it.hash == hash }
        if (kept.size == transfers.size) Decision(true) else Decision(true, next = kept)
    }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredFile(val version: Int, val transfers: List<StoredTransfer>)

    @Serializable
    private class StoredTransfer(
        val account: String,
        val nonce: Long,
        val raw: String,
        val to: String,
        val token: StoredToken?,
        val amount: String,
        val maxCost: String,
        val sentAt: Long,
    ) {
        fun toTransfer() = SentTransfer(
            account = Address.of(account),
            nonce = nonce,
            signed = SignedTransfer(raw.hexBytes()),
            to = Address.of(to),
            asset = token?.let { TransferAsset.Erc20Token(it.toToken()) } ?: TransferAsset.Native,
            amount = UInt256.of(amount),
            maxCost = UInt256.of(maxCost),
            sentAt = sentAt,
        )
    }

    /** What a token transfer needs shown again: not its icon or price, which the catalog has. */
    @Serializable
    private class StoredToken(val address: String, val symbol: String, val name: String, val decimals: Int) {
        fun toToken() = Token(Address.of(address), symbol, name, decimals, icon = null)
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        fun stored(transfer: SentTransfer) = StoredTransfer(
            account = transfer.account.hex,
            nonce = transfer.nonce,
            raw = transfer.signed.raw.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
            to = transfer.to.hex,
            token = (transfer.asset as? TransferAsset.Erc20Token)?.token?.let {
                StoredToken(it.address.hex, it.symbol, it.name, it.decimals)
            },
            amount = transfer.amount.toDecimal(),
            maxCost = transfer.maxCost.toDecimal(),
            sentAt = transfer.sentAt,
        )

        fun String.hexBytes(): ByteArray {
            require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }) { "not lower-case hex" }
            return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}
