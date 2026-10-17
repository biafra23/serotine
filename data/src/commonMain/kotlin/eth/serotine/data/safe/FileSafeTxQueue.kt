package eth.serotine.data.safe

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SafeTxQueue] kept in the file at [path] (a [VersionedJsonFile]), each request in the form a
 * signing request travels in ([WireRequest]): the Safe, its chain, the raw transaction and its
 * owners' signatures, all public. A request for a chain no [Network] is, or with a field out of
 * its range, makes the file damaged: it is set aside and the queue starts empty. One that cannot
 * be read is [SafeTxQueueList.Unreadable].
 */
class FileSafeTxQueue(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SafeTxQueue {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "Safe transaction queue",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<SigningRequest>(),
        decode = { _, text -> JSON.decodeFromString(StoredFile.serializer(), text).requests.map(::request) },
        encode = { requests ->
            JSON.encodeToString(StoredFile.serializer(), StoredFile(FORMAT_VERSION, requests.map(WireRequest::of)))
        },
    )

    override fun observe(safe: Address): Flow<SafeTxQueueList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> SafeTxQueueList.Loaded(read.value.filter { it.safe == safe })
            is VersionedJsonFile.Read.Unreadable -> SafeTxQueueList.Unreadable
        }
    }.distinctUntilChanged()

    override suspend fun save(request: SigningRequest): SigningRequest? = file.change(notSaved = null) { requests ->
        val queued = requests.firstOrNull { it.hash == request.hash }
            ?: return@change Decision(request, next = requests + request)
        val merged = queued.withSignatures(request.signatures)
        if (merged == queued) {
            Decision(queued)
        } else {
            Decision(merged, next = requests.map { if (it === queued) merged else it })
        }
    }

    override suspend fun remove(hash: Digest): Boolean = file.change(notSaved = false) { requests ->
        val kept = requests.filterNot { it.hash == hash }
        if (kept.size == requests.size) Decision(true) else Decision(true, next = kept)
    }

    override suspend fun prune(safe: Address, nonce: Long): Boolean = file.change(notSaved = false) { requests ->
        val kept = requests.filterNot { it.safe == safe && it.nonce < nonce }
        if (kept.size == requests.size) Decision(true) else Decision(true, next = kept)
    }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredFile(val version: Int, val requests: List<WireRequest>)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        /** [wire] as the request it keeps; throws on anything else, which makes the file damaged. */
        fun request(wire: WireRequest): SigningRequest {
            require(wire.v == WireRequest.VERSION) { "a request of another version" }
            val network = requireNotNull(Network.entries.firstOrNull { it.chainId == wire.chainId }) { "another chain" }
            return requireNotNull(wire.toRequest(network)) { "a field out of its range" }
        }
    }
}
