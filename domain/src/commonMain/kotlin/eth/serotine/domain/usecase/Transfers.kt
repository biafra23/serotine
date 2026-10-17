package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.hasValidChecksum
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The recipient of a transfer as the user typed it (docs/wallet-ux.md, screen 7): an address,
 * whose EIP-55 checksum must hold when it has one, or an ENS name, which [NameResolver] resolves
 * against finalized state only. This is the one use case that takes the typed text, so the
 * checksum is checked here for every screen that sends.
 */
class ResolveRecipient(private val names: NameResolver) {

    /** What [input] names, trimmed: an address as is, anything with a dot as an ENS name. */
    suspend operator fun invoke(input: String): RecipientLookup {
        val text = input.trim()
        Address.parse(text)?.let { address ->
            if (!hasValidChecksum(text)) return RecipientLookup.ChecksumMismatch
            if (address == Address.ZERO) return RecipientLookup.ZeroAddress
            return RecipientLookup.Found(address, name = null, blockNumber = null)
        }
        // A name before the 0x test: "0xfoo.eth" is a name.
        if ('.' !in text) return RecipientLookup.NotAnAddress
        return when (val resolved = names.resolve(text)) {
            // ENS's zero address is a name with no address set, not a recipient.
            is NameResolution.Resolved -> if (resolved.address == Address.ZERO) {
                RecipientLookup.NoAddress
            } else {
                RecipientLookup.Found(resolved.address, resolved.name, resolved.blockNumber)
            }
            NameResolution.NoAddress -> RecipientLookup.NoAddress
            NameResolution.NotAName -> RecipientLookup.NotAnAddress
            NameResolution.OffchainOnly -> RecipientLookup.OffchainOnly
            is NameResolution.Unavailable -> RecipientLookup.Unavailable(resolved.reason)
        }
    }
}

/** The outcome of [ResolveRecipient]. */
sealed interface RecipientLookup {

    /** [address] is the recipient; [name] the ENS name it was resolved from, at [blockNumber]. */
    data class Found(val address: Address, val name: String?, val blockNumber: Long?) : RecipientLookup

    /** Neither an address nor an ENS name. */
    data object NotAnAddress : RecipientLookup

    /** An address in mixed case whose EIP-55 checksum fails: a typo, refused. */
    data object ChecksumMismatch : RecipientLookup

    /** The zero address: what is sent to it is lost for good, so it is refused. */
    data object ZeroAddress : RecipientLookup

    /**
     * A name whose address only an off-chain gateway (CCIP-Read) gives: an off-chain source no
     * ruling allows, so it is not resolvable here (docs/wallet-ux.md, screen 7).
     */
    data object OffchainOnly : RecipientLookup

    /** An ENS name without an address. */
    data object NoAddress : RecipientLookup

    /** The name could not be resolved verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : RecipientLookup
}

/**
 * A transfer quoted for review (docs/wallet-ux.md, screen 8): the unsigned [transaction] that
 * moves [amount] of [asset] from [from] to [to] at [level]'s fees, the most its fee can be
 * ([maxFee], gas limit × fee cap), and the balances it leaves — [nativeBefore], what this
 * wallet's own transfers not mined yet may still take of it ([pendingCost]), the least the
 * native balance can be after ([nativeAfterAtLeast]), and for a token its balance before and
 * after. Every figure comes from verified state, each at its own block: the native balance
 * at [balanceBlock], the token's at [tokenBalanceBlock], the fees from the history up to
 * [feeBlock], and the transfer run as a verified call from the sender at [simulationBlock].
 *
 * Only [QuoteTransfer] makes one, and nothing copies it: what [SendTransfer] signs is its
 * [transaction], so a display field changed beside it would show one transfer and sign another.
 * A quote ages (its nonce, its fees): the screen that shows it quotes again rather than keeping
 * one for long.
 */
@ConsistentCopyVisibility
data class TransferQuote internal constructor(
    val from: Address,
    val to: Address,
    val asset: TransferAsset,
    val amount: UInt256,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val nativeAfterAtLeast: UInt256,
    val pendingCost: UInt256,
    val tokenBefore: UInt256?,
    val tokenAfter: UInt256?,
    val balanceBlock: Long?,
    val tokenBalanceBlock: Long?,
    val feeBlock: Long,
    val simulationBlock: Long?,
)

/**
 * Quotes a transfer: the sender's next nonce, the fees at [FeeLevel], the gas it takes, the
 * balances it needs, and the transfer run as a verified call from the sender, which must not
 * revert. Nothing is quoted on anything less than verified state, and an amount is in the
 * asset's smallest unit: prices never reach a transaction (CLAUDE.md, *Data sources*). On
 * [network] only, its chain id signed into the transaction.
 *
 * The sender's own transfers not mined yet ([SentTransfers]) count: the nonce is never below
 * the last of them plus one, and what they may still take of the native balance is not
 * available to this one. Those mined since are forgotten.
 */
class QuoteTransfer(
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val sentTransfers: SentTransfers,
    private val network: Network,
) {

    /** [amount] of [asset] from [from] to [to], at [level]'s fees. */
    suspend operator fun invoke(
        from: Address,
        to: Address,
        asset: TransferAsset,
        amount: UInt256,
        level: FeeLevel,
    ): TransferQuoting = coroutineScope {
        val token = (asset as? TransferAsset.Erc20Token)?.token
        // Recipients that can never give it back, refused here as well as at the typed text: this
        // is the one maker of quotes, whichever way the recipient was chosen.
        if (to == Address.ZERO) return@coroutineScope TransferQuoting.ToZeroAddress
        if (token != null && to == token.address) return@coroutineScope TransferQuoting.ToTokenContract
        // Nothing to move is nothing to pay a fee for.
        if (amount.isZero) return@coroutineScope TransferQuoting.NothingToSend
        val txTo = token?.address ?: to
        val value = if (token == null) amount else UInt256.ZERO
        val data = if (token == null) ByteArray(0) else Erc20.transferData(to, amount)
        val native = async { nativeBalances.observeBalance(from).first() }
        val tokenBalance = token?.let { async { tokenBalances.observeBalance(from, it.address).first() } }

        val nativeBefore = when (val reading = native.await()) {
            is BalanceReading.Verified -> (reading.wei.toUInt256OrNull() ?: return@coroutineScope UNPROVEN) to
                reading.blockNumber
            BalanceReading.Syncing -> return@coroutineScope TransferQuoting.Syncing
            is BalanceReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        var tokenBlock: Long? = null
        val tokenBefore = when (val reading = tokenBalance?.await()) {
            null -> null
            is TokenBalanceReading.Verified -> {
                tokenBlock = reading.blockNumber
                UInt256.parse(reading.units.decimal) ?: return@coroutineScope UNPROVEN
            }
            TokenBalanceReading.Syncing -> return@coroutineScope TransferQuoting.Syncing
            TokenBalanceReading.NotAToken -> return@coroutineScope TransferQuoting.NotAToken
            is TokenBalanceReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        if (tokenBefore != null && tokenBefore < amount) return@coroutineScope TransferQuoting.InsufficientToken
        if (token == null && nativeBefore.first < amount) return@coroutineScope TransferQuoting.InsufficientFunds
        // Past the cheap checks: the nonces and the fee history, alongside the estimate and the run.
        val nonce = async { inputs.nonce(from) }
        val mined = async { inputs.minedNonce(from) }
        val recorded = async { sentTransfers.observe().first() }
        val fees = async { inputs.fees() }

        val gas = when (val estimate = inputs.estimateGas(from, txTo, value, data)) {
            // The engine's, priced under the fork the chain runs: on Sepolia's Amsterdam a plain
            // transfer takes less than the 21000 it did before (EIP-2780), so no floor is added.
            is GasEstimate.Estimated -> estimate.gas
            is GasEstimate.Reverted -> return@coroutineScope TransferQuoting.Reverts(blockNumber = null)
            // The EVM's words: about what the sender can pay, or something else it cannot do.
            is GasEstimate.Infeasible -> return@coroutineScope if (aboutFunds(estimate.detail)) {
                TransferQuoting.InsufficientFunds
            } else {
                TransferQuoting.Infeasible(estimate.detail)
            }
            is GasEstimate.Refused -> return@coroutineScope TransferQuoting.Refused
            is GasEstimate.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(estimate.reason)
        }
        val simulationBlock = when (val simulation = inputs.simulate(from, txTo, value, data)) {
            is Simulation.Succeeded -> {
                // A token that returns false refused the transfer as surely as one that reverts.
                if (token != null && simulation.returnHex.isNotEmpty() && simulation.returnHex.all { it == '0' }) {
                    return@coroutineScope TransferQuoting.Reverts(simulation.blockNumber)
                }
                simulation.blockNumber
            }
            is Simulation.Reverted -> return@coroutineScope TransferQuoting.Reverts(simulation.blockNumber)
            Simulation.Refused -> return@coroutineScope TransferQuoting.Refused
            is Simulation.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(simulation.reason)
        }
        val quote = when (val reading = fees.await()) {
            is FeeReading.Quoted -> reading.quote
            is FeeReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        val pendingNonce = when (val reading = nonce.await()) {
            is NonceReading.Read -> reading.nonce
            is NonceReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        val minedCount = when (val reading = mined.await()) {
            is NonceReading.Read -> reading.nonce
            is NonceReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        // This wallet's own sends not mined yet: a record that cannot be read leaves the engine's.
        val unmined = (recorded.await() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.account == from && it.nonce >= minedCount }
        val next = maxOf(pendingNonce, (unmined.maxOfOrNull { it.nonce } ?: -1) + 1)
        sentTransfers.forgetMined(from, minedCount)
        val pendingCost = unmined.fold(UInt256.ZERO as UInt256?) { sum, it -> sum?.plusOrNull(it.maxCost) }
            ?: return@coroutineScope TransferQuoting.InsufficientFunds
        val available = nativeBefore.first.minusOrNull(pendingCost)
            ?: return@coroutineScope TransferQuoting.InsufficientFunds
        // An engine answer this large is no answer: the use case never throws over it.
        val maxFeePerGas = quote.maxFeePerGasOrNull(level) ?: return@coroutineScope UNPROVEN
        val maxFee = maxFeePerGas.timesOrNull(UInt256.of(gas)) ?: return@coroutineScope UNPROVEN
        val nativeCost = maxFee.plusOrNull(value) ?: return@coroutineScope TransferQuoting.InsufficientFunds
        if (available < nativeCost) return@coroutineScope TransferQuoting.InsufficientFunds
        TransferQuoting.Quoted(
            TransferQuote(
                from = from,
                to = to,
                asset = asset,
                amount = amount,
                level = level,
                transaction = Eip1559Transaction(
                    chainId = network.chainId,
                    nonce = next,
                    maxPriorityFeePerGas = quote.tip(level),
                    maxFeePerGas = maxFeePerGas,
                    gasLimit = gas,
                    to = txTo,
                    value = value,
                    data = data,
                ),
                maxFee = maxFee,
                nativeBefore = nativeBefore.first,
                nativeAfterAtLeast = available - nativeCost,
                pendingCost = pendingCost,
                tokenBefore = tokenBefore,
                tokenAfter = tokenBefore?.let { it - amount },
                balanceBlock = nativeBefore.second,
                tokenBalanceBlock = tokenBlock,
                feeBlock = quote.blockNumber,
                simulationBlock = simulationBlock,
            ),
        )
    }
}

private val UNPROVEN = TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN)

/** Whether an infeasible estimate's [detail] (geth's words) is about the sender's funds. */
private fun aboutFunds(detail: String): Boolean =
    "insufficient funds" in detail || "exceeds allowance" in detail

/** The outcome of [QuoteTransfer]. */
sealed interface TransferQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: TransferQuote) : TransferQuoting

    /** The recipient is the zero address: nobody holds its key, so the amount would be lost for good. */
    data object ToZeroAddress : TransferQuoting

    /**
     * A token sent to the token's own contract: most tokens accept it and nobody can move it out
     * again, so it is refused.
     */
    data object ToTokenContract : TransferQuoting

    /** The amount is zero: there is nothing to send, and no fee is worth paying for it. */
    data object NothingToSend : TransferQuoting

    /** The sender's balance of the network's currency cannot pay the amount and the most the fee can be. */
    data object InsufficientFunds : TransferQuoting

    /** The sender holds less of the token than the amount. */
    data object InsufficientToken : TransferQuoting

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : TransferQuoting

    /** The transfer reverts, run as a verified call from the sender (at [blockNumber]): nothing is signed. */
    data class Reverts(val blockNumber: Long?) : TransferQuoting

    /** The token's address answers as no token. */
    data object NotAToken : TransferQuoting

    /** The engine will not estimate or run it on this build. */
    data object Refused : TransferQuoting

    /** The light client has no verified head yet. */
    data object Syncing : TransferQuoting

    /** A verified figure could not be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : TransferQuoting
}

/**
 * Signs a reviewed [TransferQuote] and hands it to the network: the transaction's EIP-1559
 * signing digest is signed by the sender's key as [unlock] opens it ([Signer]), and the signed
 * bytes are broadcast. What is signed is exactly what was quoted and reviewed.
 */
class SendTransfer(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {

    /**
     * Signs and broadcasts [quote]'s transaction. It is recorded ([SentTransfers]) once signed and
     * before it is broadcast, so that a crash in between still keeps its nonce and its status; a
     * record that cannot be saved does not stop the send.
     */
    suspend operator fun invoke(quote: TransferQuote, unlock: Unlock): TransferSending {
        val transaction = quote.transaction
        val signature = when (val signed = signer.sign(quote.from, transaction.signingDigest(), unlock)) {
            is Signing.Signed -> signed.signature
            else -> return TransferSending.NotSigned(signed)
        }
        val signed = SignedTransfer(transaction.encodeSigned(signature))
        withContext(NonCancellable) {
            sentTransfers.record(
                SentTransfer(
                    account = quote.from,
                    nonce = transaction.nonce,
                    signed = signed,
                    to = quote.to,
                    asset = quote.asset,
                    amount = quote.amount,
                    maxCost = quote.maxFee.plusOrNull(transaction.value) ?: quote.maxFee,
                    sentAt = clock(),
                ),
            )
        }
        return broadcast(broadcaster, sentTransfers, signed)
    }
}

/**
 * Broadcasts [signed] again, byte for byte: what a transfer that was signed but not sent is
 * retried with, never a new quote, which could take the next nonce while the first one is
 * still on its way and pay twice.
 */
class RebroadcastTransfer(private val broadcaster: Broadcaster, private val sentTransfers: SentTransfers) {
    suspend operator fun invoke(signed: SignedTransfer): TransferSending = broadcast(broadcaster, sentTransfers, signed)
}

/**
 * Broadcasts [signed]. A broadcast rejected or refused can never be mined as signed, so its record
 * goes with it: kept, its nonce would hold every later transfer of the account behind one that
 * never comes. An unavailable one stays recorded, since it may have reached a peer all the same.
 */
private suspend fun broadcast(
    broadcaster: Broadcaster,
    sentTransfers: SentTransfers,
    signed: SignedTransfer,
): TransferSending = when (val broadcast = broadcaster.send(signed.raw)) {
    // The hash is the raw bytes' own: what the receipt is looked up by.
    is Broadcast.Sent -> TransferSending.Sent(signed.hash)
    is Broadcast.Rejected, is Broadcast.Refused -> {
        withContext(NonCancellable) { sentTransfers.forget(signed.hash) }
        TransferSending.NotSent(broadcast, signed)
    }
    is Broadcast.Unavailable -> TransferSending.NotSent(broadcast, signed)
}

/** The outcome of [SendTransfer]. */
sealed interface TransferSending {

    /** Broadcast; [hash] is its transaction hash. */
    data class Sent(val hash: String) : TransferSending

    /** Not signed, for the reason [signing] gives: nothing left the device. */
    data class NotSigned(val signing: Signing) : TransferSending

    /**
     * Signed but not broadcast, for the reason [broadcast] gives. [signed] is kept: a retry
     * broadcasts it again ([RebroadcastTransfer]), and an unavailable broadcast may have reached a
     * peer all the same, so its receipt is worth watching.
     */
    data class NotSent(val broadcast: Broadcast, val signed: SignedTransfer) : TransferSending
}

/**
 * A sent transfer (docs/wallet-ux.md, screen 9): what was sent, from its record (null when the
 * record has none: an older install, an unreadable record), and where it stands, as its verified
 * receipt says.
 */
class ObserveSentTransfer(private val sentTransfers: SentTransfers, private val receipts: ReceiptTracker) {
    operator fun invoke(hash: String): Flow<SentTransferStatus> = combine(
        sentTransfers.observe().map { list ->
            (list as? SentTransferList.Loaded)?.transfers?.lastOrNull { it.hash == hash }
        }.distinctUntilChanged(),
        receipts.observe(hash),
    ) { transfer, progress -> SentTransferStatus(transfer, progress) }
}

/** What [ObserveSentTransfer] emits: the [transfer] as recorded, and its [progress]. */
data class SentTransferStatus(val transfer: SentTransfer?, val progress: TransactionProgress)
