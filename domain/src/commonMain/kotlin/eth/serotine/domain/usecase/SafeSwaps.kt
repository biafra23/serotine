package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.RouterSwap
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Where a Safe's approval or swap stands as the chain is now (docs/swaps.md, *A Safe's swap*):
 * whether the contract it calls or approves is Uniswap's pinned SwapRouter02 on [network], its
 * code proven ([UniswapV3Chain.venue]); for a swap, what it would return now by a verified
 * QuoterV2 quote of its own route and amount, against the minimum its owners sign, and whether
 * the verified head is past its deadline. Nothing here re-quotes the swap itself: its minimum and
 * deadline are in the `SafeTx` its owners sign, and changing them would void their signatures.
 */
class CheckSafeSwap(
    private val uniswap: UniswapV3Chain,
    private val lightClient: LightClient,
    private val network: Network,
) {
    /** [action] checked, or null when it is neither an approval nor a swap. */
    suspend operator fun invoke(action: SafeTxAction): SafeSwapCheck? = when (action) {
        is SafeTxAction.TokenApproval -> approval(action.spender)
        is SafeTxAction.Swap -> swap(action.swap)
        else -> null
    }

    private suspend fun approval(spender: Address): SafeSwapCheck = when (val venue = uniswap.venue(network)) {
        is VenueReading.Verified -> if (spender == venue.deployment.swapRouter02) {
            SafeSwapCheck.ApprovalOfRouter
        } else {
            SafeSwapCheck.NotUniswap(spender)
        }
        VenueReading.NotPinned, is VenueReading.CodeMismatch -> SafeSwapCheck.NotUniswap(spender)
        is VenueReading.Unavailable -> SafeSwapCheck.Unavailable(venue.reason)
    }

    private suspend fun swap(swap: RouterSwap): SafeSwapCheck = coroutineScope {
        val deployment = when (val venue = uniswap.venue(network)) {
            is VenueReading.Verified -> venue.deployment
            VenueReading.NotPinned, is VenueReading.CodeMismatch ->
                return@coroutineScope SafeSwapCheck.NotUniswap(swap.router)
            is VenueReading.Unavailable ->
                return@coroutineScope SafeSwapCheck.Unavailable(venue.reason)
        }
        // The router is the pinned one, and ETH goes in and out through its WETH9 alone.
        val wethEnds = listOfNotNull(
            swap.route.tokenIn.takeIf { swap.sellsEther },
            swap.route.tokenOut.takeIf { swap.buysEther },
        )
        if (swap.router != deployment.swapRouter02 || wethEnds.any { it != deployment.weth9 }) {
            return@coroutineScope SafeSwapCheck.NotUniswap(swap.router)
        }
        // Read beside the quote, once the verdict needs it: a status not in yet holds back no other.
        val head = async { lightClient.observeStatus().first() }
        val now = when (val answer = uniswap.call(deployment.quoterV2, QuoterV2.quote(swap.route, swap.amountIn))) {
            is Simulation.Succeeded -> QuoterV2.quoteOf(swap.route, answer.returnHex)
                ?.let { SwapNow.Quoted(it.amountOut, answer.blockNumber) }
                ?: SwapNow.Unavailable(ReadUnavailable.UNPROVEN)
            is Simulation.Reverted -> SwapNow.NoQuote
            Simulation.Refused -> SwapNow.Unavailable(ReadUnavailable.REFUSED)
            is Simulation.Unavailable -> SwapNow.Unavailable(answer.reason)
        }
        // A verified head's time, whether peers serve proofs for it right now or not.
        val headTime = when (val status = head.await()) {
            is LightClientStatus.Synced -> status.headTimestamp
            is LightClientStatus.WaitingForProofPeers -> status.headTimestamp
            else -> null
        }
        SafeSwapCheck.SwapOnUniswap(swap, now, expired = headTime?.let { it > swap.deadline })
    }
}

/** What [CheckSafeSwap] found. */
sealed interface SafeSwapCheck {

    /** The approval lets Uniswap's pinned SwapRouter02 take the tokens, its code proven. */
    data object ApprovalOfRouter : SafeSwapCheck

    /**
     * The swap goes through Uniswap's pinned SwapRouter02, its code proven: [now] is what it would
     * return now; [expired] whether the verified head is past its deadline (null: the head's time
     * is not known yet).
     */
    data class SwapOnUniswap(val swap: RouterSwap, val now: SwapNow, val expired: Boolean?) : SafeSwapCheck {

        /** Whether what it returns now meets the minimum its owners sign; null when that is not known. */
        val meetsMinimum: Boolean?
            get() = (now as? SwapNow.Quoted)?.let { it.amountOut >= swap.minimumOut }

        /**
         * How much more than its minimum the swap returns now, when that is more than the default
         * tolerance would leave ([SlippageTolerance.DEFAULT]): what a sandwich could take of a minimum
         * that has fallen behind the market (owner ruling, 2026-10-19: said at Execute, with the
         * offer to propose it again; it may still be executed as signed). Null otherwise.
         */
        val leftOpen: UInt256?
            get() {
                val quoted = (now as? SwapNow.Quoted)?.amountOut ?: return null
                if (swap.minimumOut >= SlippageTolerance.DEFAULT.minimumOf(quoted)) return null
                return quoted - swap.minimumOut
            }
    }

    /**
     * [contract], the router called or the spender approved, is not Uniswap's pinned SwapRouter02
     * (or no Uniswap is pinned here, or its code is not the pinned code): what it does with the
     * tokens is not known here.
     */
    data class NotUniswap(val contract: Address) : SafeSwapCheck

    /** Whether the contract is Uniswap's could not be verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SafeSwapCheck
}

/** What a Safe's swap would return now, by a verified QuoterV2 quote of its route and amount. */
sealed interface SwapNow {

    /** [amountOut], quoted against the state of [blockNumber] (null: not said). */
    data class Quoted(val amountOut: UInt256, val blockNumber: Long?) : SwapNow

    /** The quote reverted: no pool on the route takes this amount now. */
    data object NoQuote : SwapNow

    /** No verified quote right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SwapNow
}
