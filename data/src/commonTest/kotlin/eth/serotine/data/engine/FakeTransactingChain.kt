package eth.serotine.data.engine

import eth.serotine.domain.model.Address

/** A [TransactingChain] whose answers a test sets; the [VerifiedChain] part is [base]'s. */
internal class FakeTransactingChain(val base: FakeChain = FakeChain()) : TransactingChain, VerifiedChain by base {
    var nonce: Long? = null
    var feeHistory: String? = null
    var estimate: EstimateAnswer = EstimateAnswer.Unavailable()
    var call: CallOutcome? = null
    var send: SendAnswer = SendAnswer.Unavailable(null)
    var receipt: String? = null
    var name: NameAnswer = NameAnswer(null, -1, verified = false, error = "not set")
    val sent = mutableListOf<ByteArray>()
    var asked = 0
    val resolved = mutableListOf<String>()
    var failure: Throwable? = null

    override fun pendingNonce(address: Address): Long? = answer { nonce }

    var mined: Long? = null

    override fun minedNonce(address: Address): Long? = answer { mined }

    override fun feeHistory(blocks: Int, percentiles: DoubleArray): String? = answer { feeHistory }

    override fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer =
        answer { estimate }

    override fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome? = answer { call }

    override fun sendRaw(raw: ByteArray): SendAnswer = answer {
        sent += raw
        send
    }

    override fun receipt(hash: String): String? = answer { receipt }

    override fun resolveName(name: String): NameAnswer = answer {
        resolved += name
        this.name
    }

    private fun <T> answer(value: () -> T): T {
        asked++
        failure?.let { throw it }
        return value()
    }
}
