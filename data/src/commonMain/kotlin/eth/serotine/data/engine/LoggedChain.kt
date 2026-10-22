package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.logs.LogSink
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.LogLevel
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [inner] with each request it is asked written to [log] under [SOURCE], named after the
 * `io.myotis.api` call it makes: at [LogLevel.INFO] the name, whether it was answered (ok) or
 * not (failed) and how long it took; at [LogLevel.DEBUG] also what was asked and what came back,
 * each cut at [MAX_DETAIL] characters. Failed means no answer: none at all, the engine's refusal
 * or an exception, which is recorded and thrown on. A verified "reverted" is an answer.
 *
 * What it records is public chain data and this node's own requests: addresses, calls, signed
 * transactions (public once broadcast) and the engine's answers. No key ever reaches a chain.
 */
internal class LoggedChain(
    private val inner: EngineChain,
    private val log: LogSink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : EngineChain {

    override fun snapshot(): EngineSnapshot =
        request("status", { "" }, { it is EngineSnapshot.Running }) { inner.snapshot() }

    override fun proveBalance(address: Address): ProvenBalance? =
        request("requestAccount", { "address=${address.hex}" }, ::answered) { inner.proveBalance(address) }

    override fun proveCode(address: Address): ProvenCode? =
        request("requestAccount, getCode", { "address=${address.hex}" }, ::answered) { inner.proveCode(address) }

    override fun call(to: Address, data: ByteArray): CallOutcome? =
        request("callDetailed", { "to=${to.hex}, data=${data.hex()}" }, ::callAnswered) { inner.call(to, data) }

    override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? = request(
        "getStorageProof",
        { "address=${address.hex}, slot=$slot" + (holder?.let { ", holder=${it.hex}" } ?: "") },
        ::answered,
    ) { inner.proveSlot(address, slot, holder) }

    override fun storageAt(address: Address, slot: ByteArray): String? =
        request("getStorageAt", { "address=${address.hex}, slot=${slot.hex()}" }, ::answered) {
            inner.storageAt(address, slot)
        }

    override fun pendingNonce(address: Address): Long? =
        request("getTransactionCount", { "address=${address.hex}, block=pending" }, ::answered) {
            inner.pendingNonce(address)
        }

    override fun minedNonce(address: Address): Long? =
        request("getTransactionCount", { "address=${address.hex}, block=latest" }, ::answered) {
            inner.minedNonce(address)
        }

    override fun feeHistory(blocks: Int, percentiles: DoubleArray): String? = request(
        "feeHistory",
        { "blocks=$blocks, block=latest, percentiles=${percentiles.joinToString(prefix = "[", postfix = "]")}" },
        ::answered,
    ) { inner.feeHistory(blocks, percentiles) }

    override fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer = request(
        "estimateGasDetailed",
        { "from=${from.hex}, to=${to.hex}, value=$valueWei wei, data=${data.hex()}" },
        { it is EstimateAnswer.Gas },
    ) { inner.estimateGas(from, to, valueWei, data) }

    override fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome? = request(
        "callDetailed",
        { "from=${from.hex}, to=${to.hex}, value=$valueWei wei, data=${data.hex()}" },
        ::callAnswered,
    ) { inner.callFrom(from, to, valueWei, data) }

    override fun sendRaw(raw: ByteArray): SendAnswer =
        request("sendRawTransactionDetailed", { "raw=${raw.hex()}" }, { it is SendAnswer.Sent }) { inner.sendRaw(raw) }

    override fun receipt(hash: String): String? =
        request("getTransactionReceipt", { "hash=$hash" }, ::answered) { inner.receipt(hash) }

    override fun resolveName(name: String): NameAnswer =
        request("ens.resolveAddress", { "name=$name, root=FINALIZED" }, { it.error == null }) {
            inner.resolveName(name)
        }

    override fun setLogIndexConfig(configJson: String): Boolean =
        request("setLogIndexConfig", { "config=$configJson" }, { it }) { inner.setLogIndexConfig(configJson) }

    override fun logIndexStatus(): String? =
        request("logIndexStatusJson", { "" }, ::jsonAnswered) { inner.logIndexStatus() }

    override fun logs(filterJson: String): String? =
        request("getLogs", { "filter=$filterJson" }, ::jsonAnswered) { inner.logs(filterJson) }

    /**
     * [block], the request [name], recorded with whether [ok] says its answer is one; [params]
     * says what was asked, built only when the log keeps [LogLevel.DEBUG].
     */
    private inline fun <T> request(name: String, params: () -> String, ok: (T) -> Boolean, block: () -> T): T {
        val started = timeSource.markNow()
        val answer = try {
            block()
        } catch (e: Throwable) {
            record(name, started, ok = false, params, e::class.simpleName ?: "an exception") { "threw $e" }
            throw e
        }
        record(name, started, ok(answer), params, why = null) { answer.toString() }
        return answer
    }

    private inline fun record(
        name: String,
        started: TimeMark,
        ok: Boolean,
        params: () -> String,
        why: String?,
        response: () -> String,
    ) {
        val took = started.elapsedNow().inWholeMilliseconds
        // The detail first: newest first, the Debug tab shows it under the line it belongs to.
        if (log.isLoggable(LogLevel.DEBUG)) {
            log.log(LogLevel.DEBUG, SOURCE, "$name(${params().cut()}) → ${response().cut()}")
        }
        val result = if (ok) "ok" else "failed" + (why?.let { " ($it)" } ?: "")
        log.log(LogLevel.INFO, SOURCE, "$name: $result, $took ms")
    }

    private fun answered(answer: Any?): Boolean = answer != null

    private fun callAnswered(answer: CallOutcome?): Boolean =
        answer is CallOutcome.Returned || answer is CallOutcome.Reverted

    private fun jsonAnswered(answer: String?): Boolean = answer != null && !answer.trimStart().startsWith("{\"error\"")

    private fun ByteArray.hex(): String = "0x" + toHex()

    private fun String.cut(): String = if (length <= MAX_DETAIL) this else take(MAX_DETAIL) + "… ($length characters)"

    companion object {
        /** The source of every record this writes. */
        const val SOURCE = "Myotis"

        /** The most of what was asked, and of what came back, one record keeps. */
        const val MAX_DETAIL = 2_000
    }
}
