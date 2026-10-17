package eth.serotine.ui.send

import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
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
     * Screen 7: the transfer [form] (from, asset, to, amount; docs/wallet-ux.md, screen 7) and the
     * [fee] level chosen, the one thing Send asks beyond the form.
     */
    data class Compose(val form: TransferForm, val fee: FeeLevel) : SendUiState {
        override val network: String
            get() = form.network
    }

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
