package eth.serotine.data.transfers

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.data.safe.WireRequest
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SentAction
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
 * [SentTransfers] kept in the file at [path] (a [VersionedJsonFile]): the signed transactions,
 * each with its signed bytes as hex, which are public once broadcast, and what it does — a
 * transfer's recipient, token and amount, or the Safe transaction an approval or execution was
 * for, in the form a signing request travels in. A damaged file is set aside and the record
 * starts empty; one that cannot be read is [SentTransferList.Unreadable] (a quote then goes by
 * the engine's nonce alone).
 *
 * Format 2 adds the Safe transactions; a file of format 1 (transfers only) reads as it is and is
 * written as 2 at its next change, which a build that reads only 1 then leaves alone.
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

    override suspend fun prune(account: Address, minedCount: Long, sentBefore: Long): Boolean =
        file.change(notSaved = false) { transfers ->
            val kept = transfers.filterNot {
                it.account == account && (it.nonce < minedCount || it.sentAt < sentBefore)
            }
            if (kept.size == transfers.size) Decision(true) else Decision(true, next = kept)
        }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredFile(val version: Int, val transfers: List<StoredTransfer>)

    /**
     * One transaction: a transfer's [to], [token] (null: the native currency) and [amount], or a
     * Safe transaction's approval or execution ([safe]) — exactly one of the two, else the file
     * is damaged.
     */
    @Serializable
    private class StoredTransfer(
        val account: String,
        val nonce: Long,
        val raw: String,
        val to: String? = null,
        val token: StoredToken? = null,
        val amount: String? = null,
        val safe: StoredSafeCall? = null,
        val maxCost: String,
        val sentAt: Long,
    ) {
        fun toTransfer() = SentTransfer(
            account = Address.of(account),
            nonce = nonce,
            signed = SignedTransfer(raw.hexBytes()),
            action = action(),
            maxCost = UInt256.of(maxCost),
            sentAt = sentAt,
        )

        private fun action(): SentAction {
            if (safe != null) {
                require(to == null && token == null && amount == null) { "a transfer and a Safe call at once" }
                return safe.toAction()
            }
            return SentAction.Transfer(
                to = Address.of(requireNotNull(to) { "a transfer without a recipient" }),
                asset = token?.let { TransferAsset.Erc20Token(it.toToken()) } ?: TransferAsset.Native,
                amount = UInt256.of(requireNotNull(amount) { "a transfer without an amount" }),
            )
        }
    }

    /** What a token transfer needs shown again: its bundled icon's name too, not its price. */
    @Serializable
    private class StoredToken(
        val address: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        val icon: String? = null,
    ) {
        fun toToken() = Token(Address.of(address), symbol, name, decimals, icon)
    }

    /** A Safe transaction's [kind] of call ([APPROVAL] or [EXECUTION]) and its [request]. */
    @Serializable
    private class StoredSafeCall(val kind: String, val request: WireRequest) {
        fun toAction(): SentAction {
            require(request.v == WireRequest.VERSION) { "a request of another version" }
            val network = requireNotNull(Network.entries.firstOrNull { it.chainId == request.chainId }) { "a chain" }
            val decoded = requireNotNull(request.toRequest(network)) { "a request out of range" }
            return when (kind) {
                APPROVAL -> SentAction.SafeApproval(decoded)
                EXECUTION -> SentAction.SafeExecution(decoded)
                else -> throw IllegalArgumentException("a Safe call of no kind")
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 2
        const val APPROVAL = "approval"
        const val EXECUTION = "execution"
        val JSON = Json

        fun stored(transfer: SentTransfer): StoredTransfer {
            val action = transfer.action
            val send = action as? SentAction.Transfer
            return StoredTransfer(
                account = transfer.account.hex,
                nonce = transfer.nonce,
                raw = transfer.signed.raw.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
                to = send?.to?.hex,
                token = (send?.asset as? TransferAsset.Erc20Token)?.token?.let {
                    StoredToken(it.address.hex, it.symbol, it.name, it.decimals, it.icon)
                },
                amount = send?.amount?.toDecimal(),
                safe = when (action) {
                    is SentAction.Transfer -> null
                    is SentAction.SafeApproval -> safeCall(APPROVAL, action.request)
                    is SentAction.SafeExecution -> safeCall(EXECUTION, action.request)
                },
                maxCost = transfer.maxCost.toDecimal(),
                sentAt = transfer.sentAt,
            )
        }

        fun safeCall(kind: String, request: SigningRequest) = StoredSafeCall(kind, WireRequest.of(request))

        fun String.hexBytes(): ByteArray {
            require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }) { "not lower-case hex" }
            return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}
