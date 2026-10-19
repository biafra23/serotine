package eth.serotine.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Activity screen's ViewModel (docs/wallet-ux.md, screen 25; issue #85): the transactions this
 * device sent for the account at [address] ([ObserveAccountHistory]), with the names the wallet
 * gives the addresses they concern ([ObserveAccounts]), as [ActivityUiState]. [formatTime] dates
 * each in the user's own time zone. No intents: a row opens its transaction's status, which is
 * navigation.
 */
class ActivityViewModel(
    private val address: Address,
    observeAccountHistory: ObserveAccountHistory,
    observeAccounts: ObserveAccounts,
    private val network: Network,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    val uiState: StateFlow<ActivityUiState> =
        combine(observeAccountHistory(address), observeAccounts(), ::render).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            ActivityUiState.Loading(shortAddress(Eip55.format(address))),
        )

    private fun render(history: HistoryList, list: AccountList): ActivityUiState {
        val names = (list as? AccountList.Loaded)?.accounts.orEmpty().associate { it.address to it.label }
        val title = names[address] ?: shortAddress(Eip55.format(address))
        return when (history) {
            HistoryList.Unreadable -> ActivityUiState.Unavailable(title, UNREADABLE)
            is HistoryList.Loaded -> ActivityUiState.Shown(
                title = title,
                rows = history.entries.map { row(it, names) },
                coverage = COVERAGE,
            )
        }
    }

    private fun row(entry: HistoryEntry, names: Map<Address, String>): ActivityRow {
        val transfer = entry.transfer
        // Its other side: whom a transfer went to, the Safe a call was for, or, seen from that Safe,
        // the account that sent it.
        val (title, other, detail) = when (val action = transfer.action) {
            is SentAction.Transfer -> Triple("Sent ${amount(action.amount, action.asset)}", action.to, "To")
            // A zero approval takes the allowance back, before the whole amount is approved.
            is SentAction.TokenApproval -> if (action.amount.isZero) {
                Triple("Allowance reset for a swap", action.spender, "Router")
            } else {
                val approved = amount(action.amount, TransferAsset.Erc20Token(action.token))
                Triple("Approved $approved", action.spender, "Router")
            }
            is SentAction.Swap -> {
                val (symbol, decimals) = shown(action.buy)
                val title = "Swapped ${amount(action.amountIn, action.sell)} for at least " +
                    "${action.minimumOut.formatUnits(decimals)} $symbol"
                Triple(title, action.router, "On Uniswap ·")
            }
            is SentAction.SafeApproval -> safeCall("Approved", transfer.account, action.request)
            is SentAction.SafeExecution -> safeCall("Executed", transfer.account, action.request)
            is SentAction.SafeDeployment -> if (transfer.account == address) {
                Triple("Deployed a Safe", action.safe, "Safe")
            } else {
                Triple("Deployed", transfer.account, "Paid by")
            }
        }
        val short = shortAddress(Eip55.format(other))
        val named = names[other]?.let { "$it · $short" } ?: short
        val (status, state) = statusOf(entry)
        return ActivityRow(
            hash = transfer.hash,
            title = title,
            detail = "$detail $named",
            about = Eip55.format(other),
            time = formatTime(transfer.sentAt),
            status = status,
            state = state,
        )
    }

    /** [units] of [asset] as screens say it: "0.25 ETH". */
    private fun amount(units: UInt256, asset: TransferAsset): String {
        val (symbol, decimals) = shown(asset)
        return "${units.formatUnits(decimals)} $symbol"
    }

    /** [asset]'s symbol as screens show it, and its decimals. */
    private fun shown(asset: TransferAsset): Pair<String, Int> = when (asset) {
        TransferAsset.Native -> network.nativeSymbol to NATIVE_DECIMALS
        is TransferAsset.Erc20Token -> displaySymbol(asset.token.symbol) to asset.token.decimals
    }

    /**
     * An approval or execution ([verb]) of [request]'s Safe transaction, sent by [sender]: its title
     * and other side as the account at [address] sees it, the Safe or the sender.
     */
    private fun safeCall(verb: String, sender: Address, request: SigningRequest): Triple<String, Address, String> =
        if (sender == address) {
            Triple("$verb a Safe transaction", request.safe, "Safe nonce ${request.nonce} ·")
        } else {
            Triple("$verb transaction ${request.nonce}", sender, "By")
        }

    private fun statusOf(entry: HistoryEntry): Pair<String, ActivityState> = when (val outcome = entry.outcome) {
        is SentOutcome.Finalized -> if (outcome.succeeded) {
            "Finalized in block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        is SentOutcome.Included -> if (outcome.succeeded) {
            "In block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        SentOutcome.NonceUsed ->
            "Mined while the app was not watching, unless another transaction took its nonce" to
                ActivityState.UNKNOWN
        SentOutcome.NotSeen -> if (entry.transfer.maybeSent) {
            "No peer confirmed its broadcast" to ActivityState.UNCONFIRMED
        } else {
            "No block seen to include it yet" to ActivityState.WAITING
        }
    }

    private companion object {
        const val NATIVE_DECIMALS = 18
        const val COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
            "from this account elsewhere, are not listed yet."
        const val UNREADABLE = "The history of your transactions cannot be read right now. Serotine keeps trying."
    }
}
