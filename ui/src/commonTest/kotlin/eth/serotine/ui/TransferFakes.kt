package eth.serotine.ui

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** One gwei, in wei. */
internal const val GWEI = 1_000_000_000L

/**
 * [TransactionInputs] answering what a test sets: the nonce 7, a base fee of 10 gwei with tips of
 * 1, 2 and 3 gwei, 21000 gas and a simulation that goes through at block 100 until it says other.
 */
internal class FakeTransactionInputs : TransactionInputs {
    var nonce = 7L
    var mined = 7L
    var fees: FeeReading = FeeReading.Quoted(
        FeeQuote(
            nextBaseFee = UInt256.of(10 * GWEI),
            tips = mapOf(
                FeeLevel.SLOW to UInt256.of(1 * GWEI),
                FeeLevel.NORMAL to UInt256.of(2 * GWEI),
                FeeLevel.FAST to UInt256.of(3 * GWEI),
            ),
            blockNumber = 99,
        ),
    )
    var estimate: GasEstimate = GasEstimate.Estimated(21_000)
    var simulation: Simulation = Simulation.Succeeded("", 100)

    /** Each transfer estimated: from, to and value. */
    val estimated = mutableListOf<Triple<Address, Address, UInt256>>()

    override suspend fun nonce(account: Address): NonceReading = NonceReading.Read(nonce)

    override suspend fun minedNonce(account: Address): NonceReading = NonceReading.Read(mined)

    override suspend fun fees(): FeeReading = fees

    override suspend fun estimateGas(from: Address, to: Address, value: UInt256, data: ByteArray): GasEstimate {
        estimated += Triple(from, to, value)
        return estimate
    }

    override suspend fun simulate(from: Address, to: Address, value: UInt256, data: ByteArray): Simulation =
        simulation
}

/**
 * A [Signer] that answers [answer], or, while [pending] is set, waits for it; each request is
 * kept in [asked], and each password it was given in [passwords].
 */
internal class FakeSigner : Signer {
    var answer: Signing = Signing.Signed(SIGNATURE)
    var pending: CompletableDeferred<Signing>? = null
    val asked = mutableListOf<Pair<Address, Unlock>>()
    val passwords = mutableListOf<String>()

    override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
        asked += account to unlock
        if (unlock is Unlock.WithPassword) passwords += unlock.password.text
        return pending?.await() ?: answer
    }

    companion object {
        /** A well-formed signature: what the fake signs everything with. */
        val SIGNATURE = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
    }
}

/** A [Broadcaster] answering each send from [answers] in turn, then [Broadcast.Sent]; the bytes it got in [sent]. */
internal class FakeBroadcaster : Broadcaster {
    val answers = ArrayDeque<Broadcast>()
    val sent = mutableListOf<ByteArray>()

    override suspend fun send(raw: ByteArray): Broadcast {
        sent += raw
        return answers.removeFirstOrNull() ?: Broadcast.Sent("0x" + "00".repeat(32))
    }
}

/** [SentTransfers] in memory. */
internal class MemorySentTransfers : SentTransfers {
    val transfers = MutableStateFlow<List<SentTransfer>>(emptyList())

    override fun observe(): Flow<SentTransferList> = transfers.map { SentTransferList.Loaded(it) }

    override suspend fun record(transfer: SentTransfer): Boolean {
        transfers.value = transfers.value.filterNot { it.hash == transfer.hash } + transfer
        return true
    }

    override suspend fun prune(account: Address, minedCount: Long, signedBefore: Long): Boolean {
        transfers.value = transfers.value.filterNot {
            it.account == account && (it.nonce < minedCount || it.sentAt < signedBefore)
        }
        return true
    }
}

/** A [NameResolver] answering from [names], [NameResolution.NoAddress] otherwise; each name asked in [asked]. */
internal class FakeNames : NameResolver {
    val names = mutableMapOf<String, NameResolution>()
    val asked = mutableListOf<String>()

    override suspend fun resolve(name: String): NameResolution {
        asked += name
        return names[name] ?: NameResolution.NoAddress
    }
}

/** A [ReceiptTracker] whose progress per hash a test sets; Pending until then. */
internal class FakeReceipts : ReceiptTracker {
    private val progress = mutableMapOf<String, MutableStateFlow<TransactionProgress>>()

    fun of(hash: String) = progress.getOrPut(hash) { MutableStateFlow(TransactionProgress.Pending) }

    override fun observe(hash: String): Flow<TransactionProgress> = of(hash)
}
