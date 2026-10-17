package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
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
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What a transfer moves: the network's own currency, or an ERC-20 [token]. */
sealed interface TransferAsset {

    /** The network's currency (ETH on Sepolia), sent as the transaction's value. */
    data object Native : TransferAsset

    /** [token], sent with its contract's `transfer`. */
    data class Erc20Token(val token: Token) : TransferAsset
}

/**
 * The recipient of a transfer as the user typed it (docs/wallet-ux.md, screen 7): an address,
 * or an ENS name, which [NameResolver] resolves against finalized state only. A typed address's
 * EIP-55 checksum is the input layer's to check before it gets here, as for every [Address].
 */
class ResolveRecipient(private val names: NameResolver) {

    /** What [input] names, trimmed: an address as is, anything with a dot as an ENS name. */
    suspend operator fun invoke(input: String): RecipientLookup {
        val text = input.trim()
        Address.parse(text)?.let { return RecipientLookup.Found(it, name = null, blockNumber = null) }
        if (text.startsWith("0x", ignoreCase = true) || '.' !in text) return RecipientLookup.NotAnAddress
        return when (val resolved = names.resolve(text)) {
            is NameResolution.Resolved -> RecipientLookup.Found(resolved.address, resolved.name, resolved.blockNumber)
            NameResolution.NoAddress -> RecipientLookup.NoAddress
            NameResolution.NotAName -> RecipientLookup.NotAnAddress
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

    /** An ENS name without an address. */
    data object NoAddress : RecipientLookup

    /** The name could not be resolved verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : RecipientLookup
}

/**
 * A transfer quoted for review (docs/wallet-ux.md, screen 8): the unsigned [transaction] that
 * moves [amount] of [asset] from [from] to [to] at [level]'s fees, the most its fee can be
 * ([maxFee], gas limit × fee cap), and the balances it leaves — [nativeBefore] and the least
 * the native balance can be after ([nativeAfterAtLeast]), and for a token its balance before
 * and after. Every figure comes from verified state: the balances at [balanceBlock], the fees
 * from the history up to [feeBlock], and the transfer run as a verified call from the sender at
 * [simulationBlock].
 */
data class TransferQuote(
    val from: Address,
    val to: Address,
    val asset: TransferAsset,
    val amount: UInt256,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val nativeAfterAtLeast: UInt256,
    val tokenBefore: UInt256?,
    val tokenAfter: UInt256?,
    val balanceBlock: Long?,
    val feeBlock: Long,
    val simulationBlock: Long?,
)

/**
 * Quotes a transfer: the sender's next nonce, the fees at [FeeLevel], the gas it takes, the
 * balances it needs, and the transfer run as a verified call from the sender, which must not
 * revert. Nothing is quoted on anything less than verified state, and an amount is in the
 * asset's smallest unit: prices never reach a transaction (CLAUDE.md, *Data sources*). On
 * [network] only, its chain id signed into the transaction.
 */
class QuoteTransfer(
    private val inputs: TransactionInputs,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
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
        // Nothing to move is nothing to pay a fee for.
        if (amount.isZero) return@coroutineScope TransferQuoting.NothingToSend
        val token = (asset as? TransferAsset.Erc20Token)?.token
        val txTo = token?.address ?: to
        val value = if (token == null) amount else UInt256.ZERO
        val data = if (token == null) ByteArray(0) else Erc20.transferData(to, amount)
        val nonce = async { inputs.nonce(from) }
        val fees = async { inputs.fees() }
        val native = async { nativeBalances.observeBalance(from).first() }
        val tokenBalance = token?.let { async { tokenBalances.observeBalance(from, it.address).first() } }

        val nativeBefore = when (val reading = native.await()) {
            is BalanceReading.Verified -> (reading.wei.toUInt256OrNull() ?: return@coroutineScope UNPROVEN) to
                reading.blockNumber
            BalanceReading.Syncing -> return@coroutineScope TransferQuoting.Syncing
            is BalanceReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        val tokenBefore = when (val reading = tokenBalance?.await()) {
            null -> null
            is TokenBalanceReading.Verified -> UInt256.parse(reading.units.decimal) ?: return@coroutineScope UNPROVEN
            TokenBalanceReading.Syncing -> return@coroutineScope TransferQuoting.Syncing
            TokenBalanceReading.NotAToken -> return@coroutineScope TransferQuoting.NotAToken
            is TokenBalanceReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        if (tokenBefore != null && tokenBefore < amount) return@coroutineScope TransferQuoting.InsufficientToken
        if (token == null && nativeBefore.first < amount) return@coroutineScope TransferQuoting.InsufficientFunds

        val gas = when (val estimate = inputs.estimateGas(from, txTo, value, data)) {
            // Never below what the transaction needs before it runs: an estimate under it (the Rust
            // engine's for a zero-value transfer, 17433) would be refused by every node.
            is GasEstimate.Estimated -> maxOf(estimate.gas, Eip1559Transaction.intrinsicGas(data))
            is GasEstimate.Reverted -> return@coroutineScope TransferQuoting.Reverts(blockNumber = null)
            is GasEstimate.Infeasible -> return@coroutineScope TransferQuoting.InsufficientFunds
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
        val next = when (val reading = nonce.await()) {
            is NonceReading.Read -> reading.nonce
            is NonceReading.Unavailable -> return@coroutineScope TransferQuoting.Unavailable(reading.reason)
        }
        // An engine answer this large is no answer: the use case never throws over it.
        val maxFeePerGas = quote.maxFeePerGasOrNull(level) ?: return@coroutineScope UNPROVEN
        val maxFee = maxFeePerGas.timesOrNull(UInt256.of(gas)) ?: return@coroutineScope UNPROVEN
        val nativeCost = maxFee.plusOrNull(value) ?: return@coroutineScope TransferQuoting.InsufficientFunds
        if (nativeBefore.first < nativeCost) return@coroutineScope TransferQuoting.InsufficientFunds
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
                nativeAfterAtLeast = nativeBefore.first - nativeCost,
                tokenBefore = tokenBefore,
                tokenAfter = tokenBefore?.let { it - amount },
                balanceBlock = nativeBefore.second,
                feeBlock = quote.blockNumber,
                simulationBlock = simulationBlock,
            ),
        )
    }
}

private val UNPROVEN = TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN)

/** The outcome of [QuoteTransfer]. */
sealed interface TransferQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: TransferQuote) : TransferQuoting

    /** The amount is zero: there is nothing to send, and no fee is worth paying for it. */
    data object NothingToSend : TransferQuoting

    /** The sender's balance of the network's currency cannot pay the amount and the most the fee can be. */
    data object InsufficientFunds : TransferQuoting

    /** The sender holds less of the token than the amount. */
    data object InsufficientToken : TransferQuoting

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
class SendTransfer(private val signer: Signer, private val broadcaster: Broadcaster) {

    /** Signs and broadcasts [quote]'s transaction. */
    suspend operator fun invoke(quote: TransferQuote, unlock: Unlock): TransferSending {
        val transaction = quote.transaction
        val signature = when (val signed = signer.sign(quote.from, transaction.signingDigest(), unlock)) {
            is Signing.Signed -> signed.signature
            else -> return TransferSending.NotSigned(signed)
        }
        val raw = transaction.encodeSigned(signature)
        return when (val broadcast = broadcaster.send(raw)) {
            // The hash is the raw bytes' own: what the receipt is looked up by.
            is Broadcast.Sent -> TransferSending.Sent(Eip1559Transaction.hashOf(raw))
            else -> TransferSending.NotSent(broadcast)
        }
    }
}

/** The outcome of [SendTransfer]. */
sealed interface TransferSending {

    /** Broadcast; [hash] is its transaction hash. */
    data class Sent(val hash: String) : TransferSending

    /** Not signed, for the reason [signing] gives: nothing left the device. */
    data class NotSigned(val signing: Signing) : TransferSending

    /** Signed but not broadcast, for the reason [broadcast] gives. */
    data class NotSent(val broadcast: Broadcast) : TransferSending
}

/** Where a sent transfer stands (docs/wallet-ux.md, screen 9), as its verified receipt says. */
class ObserveTransfer(private val receipts: ReceiptTracker) {
    operator fun invoke(hash: String): Flow<TransactionProgress> = receipts.observe(hash)
}
