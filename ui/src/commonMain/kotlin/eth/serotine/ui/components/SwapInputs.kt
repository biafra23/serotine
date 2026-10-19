package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.usecase.AccountValue

// The swap form (docs/wallet-ux.md, screens 22 and 24) as Swap and a Safe's swap both fill it in:
// the asset sold among the account's, the asset bought among those the wallet lists, and the
// tolerance typed. Pure, as TransferInputs.kt is: each ViewModel keeps its own inputs and steps.

/** An asset of a swap's form: the network's currency, or the token at [Token.address]. */
internal sealed interface SwapPick {
    data object Native : SwapPick

    data class Token(val address: Address) : SwapPick

    companion object {
        /** The pick of [contract], as a form row names it (null: the network's currency); null when no address. */
        fun of(contract: String?): SwapPick? = if (contract == null) Native else Address.parse(contract)?.let(::Token)
    }
}

/**
 * What a swap's form offers: the account's assets to sell ([sellOptions]) and the one picked
 * ([sell], null while its token is not read or not held), and every asset the wallet lists but
 * that one to buy ([buyOptions]) and the one picked ([buy], null until one is).
 */
internal data class SwapOptions(
    val sellOptions: List<AssetOption>,
    val sell: AssetOption?,
    val buyOptions: List<AssetOption>,
    val buy: AssetOption?,
)

/** The options of a swap's form as [value] reads the account, with [sell] and [buy] picked. */
internal fun swapOptions(value: AccountValue, sell: SwapPick, buy: SwapPick?, network: Network): SwapOptions {
    val sellContract = (sell as? SwapPick.Token)?.address
    val sellOptions = assetOptions(value, sellContract, network)
    // Every asset the wallet lists but the one sold.
    val buyOptions = assetOptions(value, chosen = null, network, everyToken = true)
        .filter { it.contract != sellContract }
    val buyContract = (buy as? SwapPick.Token)?.address
    return SwapOptions(
        sellOptions = sellOptions,
        sell = sellOptions.firstOrNull { it.contract == sellContract },
        buyOptions = buyOptions,
        buy = buy?.let { buyOptions.firstOrNull { option -> option.contract == buyContract } },
    )
}

/** Why the tolerance [typed] is none, or null: nothing typed yet, or a tolerance. */
internal fun toleranceError(typed: String): String? {
    val text = typed.trim()
    return TOLERANCE_ERROR.takeIf { text.isNotEmpty() && SlippageTolerance.parsePercent(text) == null }
}

/** What holds the sale back while no asset to sell is picked: [holder]'s tokens ("this Safe") not read, or not held. */
internal fun sellNote(sell: AssetOption?, tokensRead: Boolean, holder: String): String? = when {
    sell != null -> null
    !tokensRead -> "Checking ${holder}'s tokens…"
    else -> "The token asked for is not among ${holder}'s tokens: choose another."
}
