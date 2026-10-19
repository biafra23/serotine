package eth.serotine.ui.swap

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the Swap screen renders (docs/swaps.md; screens 22 and 23): the swap being composed,
 * then the review of the approval it needs first, if any, and of the swap itself, each with the
 * unlock that signs it. Every leaf is display-ready. Amounts are token units throughout; a fiat
 * value is only ever a hint beside one, and no amount comes from a price (CLAUDE.md, *Data
 * sources*). While a review is shown it holds the wallet password being typed, which the
 * ViewModel drops as soon as the screen is not shown.
 */
sealed interface SwapUiState {

    /** The network's name ("Sepolia"), which the screen says it swaps on. */
    val network: String

    /** The account and its assets have not been read yet. */
    data class Loading(override val network: String) : SwapUiState

    /**
     * Nothing can be swapped from this account, for the reason [message] gives: it is watch-only,
     * a Safe (whose swaps are Safe transactions, proposed from its own Swap), gone from the wallet, or the
     * account list cannot be read.
     */
    data class CannotSwap(override val network: String, val message: String) : SwapUiState

    /**
     * Screen 22: the swap [form] and the [fee] level. [earlier] is the hash of a transaction of
     * the account's that holds the swap back, with its status offered: one that may still be on
     * its way, an approval or an earlier swap of the token not mined yet.
     */
    data class Compose(val form: SwapForm, val fee: FeeLevel, val earlier: EarlierItem? = null) : SwapUiState {
        override val network: String
            get() = form.network
    }

    /**
     * The approval the swap needs before it (docs/swaps.md, *What both venues share*): the router
     * may take only [allowance] of the token, and this transaction lets it take [amount] (zero
     * first when [resetNote] says the token needs that), its own transaction, quoted, signed and
     * sent as any is. The swap is quoted once a block has it.
     */
    data class ApprovalReview(
        override val network: String,
        val from: SendFrom,
        val token: AssetChoice,
        val amount: String,
        val allowance: String,
        val spender: SpenderItem,
        val resetNote: String?,
        val maxFee: String,
        val feeDetail: String,
        val balance: BalanceChange,
        val simulation: String,
        override val unlock: UnlockStep,
        override val refreshing: Boolean = false,
        override val signing: Boolean = false,
        override val notSent: NotSentItem? = null,
    ) : SwapUiState, Signable

    /**
     * Screen 23: a verified quote of the swap. [sold] exact in [sell]'s units; [receive] what the
     * exact transaction returned, run as a verified call ([ranAt]); [minimum] the least it may
     * return, at the [tolerance]; the [route] through the factory's pools; the router it goes
     * through ([spender]), and for a token sold, [allowanceNote] saying the allowance it already
     * has covers the amount; [deadline] when the router refuses it; the most the fee can be and what
     * it is made of; the verified [balances] before → after. [pending] what earlier transactions
     * not mined yet may still take. [netNote] when the route was chosen without its fee.
     * [priceImpact] says how far the quote falls below the pools' spot prices, or that it is not
     * known; [priceHint] what the prices the wallet shows say of the quote (display only).
     */
    data class SwapReview(
        override val network: String,
        val from: SendFrom,
        val sell: AssetChoice,
        val sold: String,
        val buy: AssetChoice,
        val receive: String,
        val ranAt: String,
        val minimum: String,
        val tolerance: String,
        val route: List<RouteStep>,
        val routeNote: String,
        val priceImpact: String,
        val priceHint: String? = null,
        val spender: SpenderItem,
        val allowanceNote: String? = null,
        val deadline: String,
        val maxFee: String,
        val feeDetail: String,
        val balances: List<BalanceChange>,
        val pending: String? = null,
        val netNote: String? = null,
        override val unlock: UnlockStep,
        override val refreshing: Boolean = false,
        override val signing: Boolean = false,
        override val notSent: NotSentItem? = null,
    ) : SwapUiState, Signable

    /** Broadcast: [hash] is the swap's, which the status screen follows. */
    data class Sent(override val network: String, val hash: String) : SwapUiState
}

/** What a review that signs has: its unlock and where its signature stands. */
sealed interface Signable {
    val unlock: UnlockStep
    val refreshing: Boolean
    val signing: Boolean
    val notSent: NotSentItem?

    /** Something is typed, and nothing else runs or has run: the password may sign. */
    val canSign: Boolean
        get() = idle && unlock.password.text.isNotEmpty()

    /** The biometric prompt may be offered now. */
    val canSignWithBiometrics: Boolean
        get() = idle && unlock.biometrics

    /** Neither a refresh nor a signature runs, and nothing was signed yet. */
    val idle: Boolean
        get() = !refreshing && !signing && notSent == null
}

/**
 * The swap form (screen 22), display-ready: [from] is fixed; [sellOptions] the account's assets
 * with their verified balances, [sell] the chosen one; [buyOptions] the assets the wallet lists
 * (its native currency, the built-in tokens and those the user added), [buy] the chosen one;
 * [amount] typed in [sell]'s units ([amountError] why it is no amount), with [amountHint] its
 * value (display only) and [canMax]; [tolerance] the slippage tolerance typed as a percentage
 * ([toleranceError] why it is none). [note] says what holds the pair back, [quoting] while the
 * review is quoted, [error] why the last one was refused, [canReview] when it can be asked for.
 * [fixed] when the pair and the amount are set and only the tolerance may change: a Safe's swap
 * proposed again in place of one queued.
 */
data class SwapForm(
    val network: String,
    val from: SendFrom,
    val sellOptions: List<AssetChoice>,
    val sell: AssetChoice?,
    val buyOptions: List<AssetChoice>,
    val buy: AssetChoice?,
    val amount: String,
    val tolerance: String,
    val amountError: String? = null,
    val amountHint: String? = null,
    val canMax: Boolean = false,
    val toleranceError: String? = null,
    val note: String? = null,
    val quoting: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val canReview: Boolean = false,
    val fixed: Boolean = false,
)

/**
 * A transaction of the account's that holds the swap back: [hash], what to open it as
 * ([label], "Open the approval"), and whether it is only waiting for a block ([waiting]) or may
 * still be on its way, to be tried again from its status.
 */
data class EarlierItem(val hash: String, val label: String, val waiting: Boolean)

/** The router a swap goes through, or an approval lets take the tokens: [name] and its EIP-55 [address]. */
data class SpenderItem(val name: String, val address: String)

/**
 * One pool of a swap's route: [pair] ("USDC → WETH"), its [fee] tier ("0.05 %") and the EIP-55
 * [pool] the pinned factory returned.
 */
data class RouteStep(val pair: String, val fee: String, val pool: String)
