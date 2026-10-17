package eth.serotine.ui.send

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.UnlockStep

/**
 * Everything the Send screen renders (docs/wallet-ux.md, screens 7 and 8): the transfer being
 * composed, then the verified quote of it under review, with the unlock that signs it. Every
 * leaf is display-ready. Amounts are token units throughout; a fiat value is only ever a hint
 * beside one (CLAUDE.md, *Data sources*). While the review is shown, [Review.unlock] holds the
 * wallet password being typed, under the screen protections of a screen that takes it, and the
 * ViewModel drops it as soon as the screen is not shown.
 */
sealed interface SendUiState {

    /** The network's name ("Sepolia"), which the screen says it sends on. */
    val network: String

    /** The account and its assets have not been read yet. */
    data class Loading(override val network: String) : SendUiState

    /**
     * Nothing can be sent from this account, for the reason [message] gives: it is watch-only,
     * a Safe (whose transfers are Safe transactions), gone from the wallet, or the account list
     * cannot be read.
     */
    data class CannotSend(override val network: String, val message: String) : SendUiState

    /**
     * Screen 7. [from] is fixed. [assets] are the account's: its native currency and the tokens
     * it holds, each with its verified balance; [asset] the chosen one (null while the one asked
     * for is not known yet, or is not among them: [assetNote] says which). [to] is the recipient as
     * typed; [recipient] what it is once it parses as an address or resolves as a name, and
     * [toNote] what the field says under it — an error when [toError], with [canRetryLookup] when
     * asking again may help. [accounts] are the wallet's other accounts, to pick one as the
     * recipient. [amount] is typed in token units ([amountError] why it is no amount), with
     * [amountHint] its value in the display currency (display only) and [canMax] when the whole
     * verified balance can be filled in. [fee] is the chosen level (null where there is none to
     * choose: a Safe's proposal, whose execution the executing account pays for). [quoting] while
     * the quote for the review is made, and [error] why the last one was refused; [canReview] when
     * it can be asked for.
     */
    data class Compose(
        override val network: String,
        val from: SendFrom,
        val assets: List<AssetChoice>,
        val asset: AssetChoice?,
        val to: String,
        val accounts: List<AccountChoice>,
        val amount: String,
        val fee: FeeLevel?,
        val assetNote: String? = null,
        val recipient: RecipientItem? = null,
        val toNote: String? = null,
        val toError: Boolean = false,
        val canRetryLookup: Boolean = false,
        val amountError: String? = null,
        val amountHint: String? = null,
        val canMax: Boolean = false,
        val quoting: Boolean = false,
        val error: String? = null,
        val canReview: Boolean = false,
    ) : SendUiState

    /**
     * Screen 8: a verified quote of the transfer. [amount] is exact, in [asset]'s units with its
     * symbol; [recipient] in full with the name it was resolved from; [maxFee] the most the fee
     * can be, in the network's currency, and [feeDetail] what it is made of. [balances] are the
     * verified simulation's before → after, each with its block; [pending] what earlier
     * transfers not mined yet may still take, if any; [simulation] how the transfer ran as a
     * verified call. [unlock] is how it is signed. [refreshing] while the quote is made again,
     * [signing] while it is signed and broadcast, and [notSent] once it was signed but not
     * broadcast: then the same signed bytes are all that can be sent.
     */
    data class Review(
        override val network: String,
        val from: SendFrom,
        val amount: String,
        val asset: AssetChoice,
        val recipient: RecipientItem,
        val maxFee: String,
        val feeDetail: String,
        val balances: List<BalanceChange>,
        val simulation: String,
        val unlock: UnlockStep,
        val pending: String? = null,
        val refreshing: Boolean = false,
        val signing: Boolean = false,
        val notSent: NotSentItem? = null,
    ) : SendUiState {

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

    /** Broadcast: [hash] is the transaction's, which the status screen follows. */
    data class Sent(override val network: String, val hash: String) : SendUiState
}

/** The account sent from: its [label] and EIP-55 [address]. */
data class SendFrom(val label: String, val address: String)

/**
 * One asset the account can send: its [symbol], [name] and bundled [icon] (null: a monogram), a
 * token's EIP-55 [contract] (null for the network's currency), the [balance] held ("1.5 ETH",
 * or why there is none yet), the block it was verified at ([verifiedAt], "block 9,412,118"), and
 * [warning] for a token the user added whose symbol is a built-in token's.
 */
data class AssetChoice(
    val symbol: String,
    val name: String,
    val icon: String?,
    val balance: String,
    val contract: String? = null,
    val verifiedAt: String? = null,
    val warning: String? = null,
)

/** One of the wallet's accounts, offered as the recipient: its [label] and EIP-55 [address]. */
data class AccountChoice(val label: String, val address: String)

/**
 * The recipient: its EIP-55 [address], in full wherever it is confirmed; the ENS [name] it was
 * resolved from, if any, and [resolvedAt], the finalized block it was resolved at; its [label]
 * when it is one of the wallet's accounts; and [note], what to know about it ("The account you
 * send from").
 */
data class RecipientItem(
    val address: String,
    val name: String? = null,
    val resolvedAt: String? = null,
    val label: String? = null,
    val note: String? = null,
)

/**
 * One balance of the verified simulation: [what] ("ETH balance"), [before] and [after] it, and
 * the block the balance before was verified at ([verifiedAt]).
 */
data class BalanceChange(val what: String, val before: String, val after: String, val verifiedAt: String)
