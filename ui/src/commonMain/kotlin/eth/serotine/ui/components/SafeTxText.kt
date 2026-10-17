package eth.serotine.ui.components

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.CallRefusal
import eth.serotine.domain.usecase.KnownTokenList
import eth.serotine.domain.usecase.SafeTxRefusal

/**
 * What a Safe transaction does, as decoded here from its raw fields (docs/wallet-ux.md, screen
 * 17), display-ready: [title] ("Send 40 USDC"), the [addresses] it names, each with what it is,
 * [notes] on what else it does ("The threshold becomes 2", its call data), and [warning], what to
 * know before anyone signs it (a delegate call, a module that can move the funds), or null. [icon]
 * and [symbol] are the asset's for a transfer of a known one, null otherwise. Never taken from
 * anything that came with the request: a request carries no description.
 */
data class SafeTxActionItem(
    val title: String,
    val addresses: List<ActionAddress> = emptyList(),
    val notes: List<String> = emptyList(),
    val warning: String? = null,
    val icon: String? = null,
    val symbol: String? = null,
)

/** An address a Safe transaction names: [what] it is ("To", "New owner") and its EIP-55 [address]. */
data class ActionAddress(val what: String, val address: String)

/**
 * The gas and refund terms a Safe transaction carries beside its call (docs/wallet-ux.md, screen
 * 17), display-ready: [lines] ("safeTxGas: 50,000") and the [addresses] they name (the gas token,
 * the refund receiver).
 */
data class GasTermsItem(val lines: List<String>, val addresses: List<ActionAddress>)

/**
 * A token a Safe transaction's transfer is shown in the units of: [token] as the wallet knows it,
 * and whether it is a token the user added whose symbol is a built-in token's ([sharesBuiltInSymbol]),
 * which it is not: a contract may call itself anything.
 */
internal data class DecodingToken(val token: Token, val sharesBuiltInSymbol: Boolean = false)

/**
 * [action], a transaction of a Safe at [nonce], as screens show it: a transfer's amount in the
 * token's units where the wallet knows the token ([tokens], the known ones by address), marked when
 * the token only takes a built-in token's symbol; its smallest units where it does not; and every
 * address it names in full.
 */
internal fun safeTxActionItem(
    action: SafeTxAction,
    nonce: Long,
    tokens: List<DecodingToken>,
    network: Network,
): SafeTxActionItem = when (action) {
    is SafeTxAction.NativeTransfer -> SafeTxActionItem(
        title = "Send ${action.amount.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}",
        addresses = listOf(ActionAddress("To", Eip55.format(action.to))),
        icon = network.nativeIcon,
        symbol = network.nativeSymbol,
    )
    is SafeTxAction.TokenTransfer -> {
        val known = tokens.firstOrNull { it.token.address == action.token }
        val token = known?.token
        val addresses = listOf(
            ActionAddress("To", Eip55.format(action.to)),
            ActionAddress("Token contract", Eip55.format(action.token)),
        )
        if (token == null) {
            SafeTxActionItem(
                title = "Send ${grouped(action.amount)} units of a token",
                addresses = addresses,
                notes = listOf(
                    "Serotine does not know the token at this contract: the amount is in its smallest units, " +
                        "and how many decimals it has is not known here.",
                ),
            )
        } else {
            val symbol = displaySymbol(token.symbol)
            SafeTxActionItem(
                title = "Send ${action.amount.formatUnits(token.decimals)} $symbol",
                addresses = addresses,
                // As Send's asset row says it: the symbol is the contract's claim.
                warning = if (known.sharesBuiltInSymbol) "Not the built-in $symbol: another contract" else null,
                icon = token.icon,
                symbol = symbol,
            )
        }
    }
    is SafeTxAction.SettingsChange -> settingsChange(action.change)
    SafeTxAction.Rejection -> SafeTxActionItem(
        title = "Reject: use up nonce $nonce",
        notes = listOf(
            "Nothing is called and nothing moves. Executed, it uses up nonce $nonce, so no other " +
                "transaction at that nonce can ever run.",
        ),
    )
    is SafeTxAction.ContractCall -> SafeTxActionItem(
        title = "Call a contract",
        addresses = listOf(ActionAddress("Contract", Eip55.format(action.to))),
        notes = listOfNotNull(
            action.value.takeUnless { it.isZero }
                ?.let { "With ${it.formatUnits(NATIVE_DECIMALS)} ${network.nativeSymbol}" },
            callData(action.selector, action.dataSize),
            "Not decoded here: what it does is up to the contract's code.",
        ),
    )
    is SafeTxAction.DelegateCall -> SafeTxActionItem(
        title = "Delegate call",
        addresses = listOf(ActionAddress("Code run as the Safe", Eip55.format(action.to))),
        notes = listOf(callData(action.selector, action.dataSize)),
        warning = "A delegate call: the Safe would run this contract's code as itself, with its storage, " +
            "owners and funds, and the code can change any of them. Serotine shows it and decodes it no " +
            "further, and does not sign, approve or execute it.",
    )
}

private fun settingsChange(change: SafeSettingsChange): SafeTxActionItem = when (change) {
    is SafeSettingsChange.OwnerAdded -> SafeTxActionItem(
        title = "Add an owner",
        addresses = listOf(ActionAddress("New owner", Eip55.format(change.owner))),
        notes = listOf(threshold(change.threshold)),
    )
    is SafeSettingsChange.OwnerRemoved -> SafeTxActionItem(
        title = "Remove an owner",
        addresses = listOf(ActionAddress("Owner removed", Eip55.format(change.owner))),
        notes = listOf(threshold(change.threshold)),
    )
    is SafeSettingsChange.OwnerSwapped -> SafeTxActionItem(
        title = "Replace an owner",
        addresses = listOf(
            ActionAddress("Owner removed", Eip55.format(change.old)),
            ActionAddress("New owner", Eip55.format(change.new)),
        ),
    )
    is SafeSettingsChange.ThresholdChanged ->
        SafeTxActionItem(title = "Change the threshold", notes = listOf(threshold(change.threshold)))
    is SafeSettingsChange.ModuleEnabled -> SafeTxActionItem(
        title = "Enable a module",
        addresses = listOf(ActionAddress("Module", Eip55.format(change.module))),
        warning = "A module can move the Safe's funds without the owners' signatures.",
    )
    is SafeSettingsChange.ModuleDisabled -> SafeTxActionItem(
        title = "Disable a module",
        addresses = listOf(ActionAddress("Module", Eip55.format(change.module))),
        warning = "Whatever this module does for the Safe stops: a spending limit, a delay, a recovery path.",
    )
    is SafeSettingsChange.GuardSet -> change.guard?.let { guard ->
        SafeTxActionItem(
            title = "Set a guard",
            addresses = listOf(ActionAddress("Guard", Eip55.format(guard))),
            warning = "A guard checks every transaction the owners sign: one that refuses them all locks the Safe.",
        )
    } ?: SafeTxActionItem(
        title = "Remove the guard",
        warning = "Whatever the guard enforces on the Safe's transactions stops: a limit, an allowlist, a delay.",
    )
    is SafeSettingsChange.FallbackHandlerSet -> change.handler?.let { handler ->
        SafeTxActionItem(
            title = "Set the fallback handler",
            addresses = listOf(ActionAddress("Fallback handler", Eip55.format(handler))),
            notes = listOf("It answers the calls the Safe has no function for."),
            warning = "The fallback handler answers the signature checks (EIP-1271) other contracts make for this " +
                "Safe: one that accepts every signature lets anyone spend what the Safe has approved (Permit2, " +
                "permits, orders), without its owners.",
        )
    } ?: SafeTxActionItem(title = "Remove the fallback handler")
}

private fun threshold(threshold: Long) = "The threshold becomes $threshold"

private fun callData(selector: String?, size: Int): String =
    selector?.let { "Function 0x$it, ${bytes(size)} of call data" }
        ?: "${bytes(size)} of call data, shorter than a function's selector"

private fun bytes(count: Int) = if (count == 1) "1 byte" else "${grouped(count.toLong())} bytes"

/** [terms] as screens show them, before the warning that says what they can do. */
internal fun gasTermsItem(terms: SafeTxGasTerms): GasTermsItem = GasTermsItem(
    lines = listOf(
        "safeTxGas: ${grouped(terms.safeTxGas)}",
        "baseGas: ${grouped(terms.baseGas)}",
        "gasPrice: ${grouped(terms.gasPrice)}" + if (terms.gasToken == null) " wei" else " of the gas token's units",
        if (terms.gasToken == null) "A refund is paid in ether" else "A refund is paid in the gas token",
        if (terms.receiver == null) "A refund goes to whoever executes it" else "A refund goes to the refund receiver",
    ),
    addresses = listOfNotNull(
        terms.gasToken?.let { ActionAddress("Gas token", Eip55.format(it)) },
        terms.receiver?.let { ActionAddress("Refund receiver", Eip55.format(it)) },
    ),
)

/** What gas and refund terms can do, and that this wallet does not sign them (docs/wallet-ux.md, screen 17). */
internal const val GAS_TERMS_WARNING =
    "Gas and refund terms: a refund pays (gas used + baseGas) × gasPrice to the refund receiver, bounded only " +
        "by what the Safe holds, and with a gas price the call gets exactly safeTxGas; a safeTxGas without one " +
        "lets a failing call use up the nonce. Serotine proposes none, and does not sign, approve or execute a " +
        "transaction that carries them."

/** Why a Safe transaction was not imported, signed, approved or executed, display-ready. */
internal fun safeTxRefusalText(refusal: SafeTxRefusal, network: Network): String = when (refusal) {
    SafeTxRefusal.Malformed -> "This text is not a Safe signing request."
    is SafeTxRefusal.UnknownVersion ->
        "A signing request of a newer version (${refusal.version}): update Serotine to read it."
    is SafeTxRefusal.WrongChain ->
        "A request for another network (chain id ${refusal.chainId}), not ${network.displayName}."
    SafeTxRefusal.NotASafe -> "The address holds no Safe Serotine recognises, as its verified code and storage say."
    is SafeTxRefusal.OtherSafe ->
        "This request is for another Safe, ${Eip55.format(refusal.safe)}: import it from that Safe's own screen."
    is SafeTxRefusal.NonceUsed ->
        "Its nonce is used: the Safe is at nonce ${refusal.safeNonce} already, so it can never run."
    is SafeTxRefusal.NotNext ->
        "Not next: the Safe is at nonce ${refusal.safeNonce}, and the transactions before this one run first."
    is SafeTxRefusal.NotAnOwner -> "${Eip55.format(refusal.address)} is not one of the Safe's current owners."
    is SafeTxRefusal.BadSignature ->
        "The signature said to be ${Eip55.format(refusal.owner)}'s is not that owner's over this transaction: " +
            "refused, and nothing of the request is kept."
    SafeTxRefusal.DelegateCall -> "A delegate call: Serotine does not sign, approve or execute one."
    is SafeTxRefusal.AlreadySigned -> "${Eip55.format(refusal.owner)} has signed it already."
    SafeTxRefusal.AlreadyApproved -> "This owner has approved it on chain already."
    SafeTxRefusal.AlreadySent ->
        "Sent from this wallet already and in no block yet: sending it again would only pay a fee to revert."
    is SafeTxRefusal.ThresholdNotMet ->
        "${refusal.confirmations} of the ${refusal.threshold} confirmations it needs: not enough to execute."
    SafeTxRefusal.GasTerms -> "It carries gas or refund terms: Serotine does not sign, approve or execute those."
    SafeTxRefusal.Syncing -> "The light client is not synced yet: try again in a moment."
    is SafeTxRefusal.Unavailable -> unavailableText(refusal.reason)
    is SafeTxRefusal.NotQuoted -> callRefusalText(refusal.refusal, network)
}

/** Why the transaction an account here would send was not quoted, display-ready. */
internal fun callRefusalText(refusal: CallRefusal, network: Network): String = when (refusal) {
    CallRefusal.InsufficientFunds -> "Not enough ${network.nativeSymbol} in this account for the most the fee can " +
        "be. Transactions not mined yet count too."
    is CallRefusal.Infeasible -> "This transaction cannot succeed: ${refusal.detail}"
    is CallRefusal.Reverts -> "It reverts, run as a verified call" +
        refusal.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty() + ". Nothing was signed."
    CallRefusal.Refused -> "The light client on this device cannot quote this transaction."
    CallRefusal.Syncing -> "The light client is not synced yet: try again in a moment."
    is CallRefusal.Unavailable -> unavailableText(refusal.reason)
    is CallRefusal.EarlierMaybeSent -> EARLIER_MAYBE_SENT
}

/**
 * Why nothing new is quoted from an account whose earlier transaction may still be on its way
 * ([CallRefusal.EarlierMaybeSent]), where the screen offers no way to its status: Send on that
 * account does.
 */
internal const val EARLIER_MAYBE_SENT = "An earlier transaction from this account may still be on its way: no " +
    "peer answered when it was broadcast, and no block has it yet. Try it again from its status (Send on this " +
    "account opens it) rather than sign a new one, which could pay twice."

/** Why a verified figure could not be had right now, display-ready. */
internal fun unavailableText(reason: ReadUnavailable): String = when (reason) {
    ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be verified."
    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be verified."
    ReadUnavailable.UNPROVEN -> "A figure could not be verified this time: try again."
    ReadUnavailable.REFUSED -> "The light client on this device cannot read this."
}

/** [amount] in whole digits, grouped by thousands ("1,500,000"). */
private fun grouped(amount: UInt256): String =
    amount.toDecimal().reversed().chunked(3).joinToString(",").reversed()

/**
 * The tokens a Safe transaction's transfer is shown in the units of: every token the wallet knows,
 * hidden ones too, but no built-in entry its contract contradicts; an added one that takes a
 * built-in token's symbol marked as such.
 */
internal fun KnownTokenList.forDecoding(): List<DecodingToken> =
    tokens.filter { it.check !is BuiltInCheck.Disagrees }.map { DecodingToken(it.token, it.sharesBuiltInSymbol) }
