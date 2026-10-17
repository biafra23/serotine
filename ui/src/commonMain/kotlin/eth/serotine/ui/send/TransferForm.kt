package eth.serotine.ui.send

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.Valuation
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.displayName
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.formatUnits
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.valuationText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

// The transfer form (docs/wallet-ux.md, screen 7) as Send and a Safe's proposal of a transfer
// (screen 16) both fill it in: the account's assets as options, the recipient typed or picked and
// resolved, the amount typed in token units, and what the form says about each. Pure: each
// ViewModel keeps its own inputs and its own steps, and draws its form from these.

/** An asset the account can send, with what the form shows of it. */
internal data class AssetOption(
    val asset: TransferAsset,
    val symbol: String,
    val name: String,
    val icon: String?,
    val decimals: Int,
    val balance: UInt256?,
    val balanceText: String,
    val verifiedAt: String?,
    val valuation: Valuation?,
    val warning: String?,
) {
    /** The token's contract, null for the network's currency. */
    val contract: Address?
        get() = (asset as? TransferAsset.Erc20Token)?.token?.address

    /** As the form lists it. */
    fun choice() = AssetChoice(
        symbol = symbol,
        name = name,
        icon = icon,
        balance = balanceText,
        contract = contract?.let(Eip55::format),
        verifiedAt = verifiedAt,
        warning = warning,
    )
}

/**
 * The network's currency and every token the account holds, as [value] reads them, and the token
 * [chosen] whatever its balance says: it was asked for, and its row says what is known of it.
 */
internal fun assetOptions(value: AccountValue, chosen: Address?, network: Network): List<AssetOption> {
    val native = value.native
    val nativeOption = AssetOption(
        asset = TransferAsset.Native,
        symbol = network.nativeSymbol,
        name = network.nativeName,
        icon = network.nativeIcon,
        decimals = NATIVE_DECIMALS,
        balance = (native as? BalanceReading.Verified)?.wei?.toUInt256OrNull(),
        balanceText = native?.let { balanceText(it, network) } ?: CHECKING,
        verifiedAt = (native as? BalanceReading.Verified)?.let { block(it.blockNumber) },
        valuation = value.nativeValue,
        warning = null,
    )
    val tokens = value.tokens.mapNotNull { (holding, valuation) ->
        val token = holding.token
        val reading = holding.reading
        val verified = reading as? TokenBalanceReading.Verified
        if (token.address != chosen && (verified == null || verified.units.isZero)) return@mapNotNull null
        val symbol = displaySymbol(token.symbol)
        AssetOption(
            asset = TransferAsset.Erc20Token(token),
            symbol = symbol,
            name = displayName(token.name),
            icon = token.icon,
            decimals = token.decimals,
            balance = verified?.let { UInt256.parse(it.units.decimal) },
            balanceText = when (reading) {
                is TokenBalanceReading.Verified -> formatUnits(reading.units.decimal, token.decimals, symbol)
                null, TokenBalanceReading.Syncing -> CHECKING
                TokenBalanceReading.NotAToken -> "Not a token"
                is TokenBalanceReading.Unavailable -> "Not verified"
            },
            verifiedAt = verified?.blockNumber?.let(::block),
            valuation = valuation,
            warning = if (holding.sharesBuiltInSymbol) "Not the built-in $symbol: another contract" else null,
        )
    }
    return listOf(nativeOption) + tokens
}

/** What the typed recipient [text] is, as far as is known yet. */
internal sealed interface RecipientState {
    val text: String

    /** Nothing typed. */
    data object Empty : RecipientState {
        override val text = ""
    }

    /** A name, being resolved. */
    data class Resolving(override val text: String) : RecipientState

    /** [text] is [result]. */
    data class Done(override val text: String, val result: RecipientLookup) : RecipientState
}

/**
 * The recipient as [typed] (the text, trimmed, and how often the user asked to resolve it again),
 * resolved by [lookUp]: an address at once, a name once the typing paused, so that a name is not
 * resolved for every letter of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun recipientStates(
    typed: Flow<Pair<String, Int>>,
    lookUp: suspend (String) -> RecipientLookup,
): Flow<RecipientState> = typed
    .distinctUntilChanged()
    .transformLatest { (text, _) ->
        if (text.isEmpty()) {
            emit(RecipientState.Empty)
            return@transformLatest
        }
        // An address has no dot; ResolveRecipient takes anything with one as a name.
        if ('.' in text) {
            emit(RecipientState.Resolving(text))
            delay(NAME_PAUSE_MS)
        }
        emit(RecipientState.Done(text, lookUp(text)))
    }

/** What [text] names, resolved; never throws. */
internal suspend fun ResolveRecipient.lookUpSafely(text: String): RecipientLookup {
    // An all-caps copy upper-cases the prefix too.
    val normalized = if (text.startsWith("0X")) "0x" + text.drop(2) else text
    return try {
        this(normalized)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the port promises not to throw; the backstop
    }
}

/** A recipient resolved again before a quote, from the name it was resolved from. */
internal sealed interface ResolvedAgain {

    /** [found]: the name still has the address shown, at [found]'s block. */
    data class Same(val found: RecipientLookup.Found) : ResolvedAgain

    /** It moved, or cannot be verified again right now: [message], and nothing is signed for it. */
    data class Changed(val message: String) : ResolvedAgain
}

/**
 * [found] resolved again by [lookUp] when it came from a name: what is signed is the address the
 * name has now, and one that moved is said, not signed. One typed as an address stands as it is.
 */
internal suspend fun resolveAgain(
    found: RecipientLookup.Found,
    lookUp: suspend (String) -> RecipientLookup,
): ResolvedAgain {
    val name = found.name ?: return ResolvedAgain.Same(found)
    val again = lookUp(name)
    if (again is RecipientLookup.Found && again.address == found.address) return ResolvedAgain.Same(again)
    return ResolvedAgain.Changed(
        if (again is RecipientLookup.Unavailable) {
            "“$name” could not be resolved with verified calls again just now, so nothing can be " +
                "signed for it: review it again in a moment."
        } else {
            "“$name” no longer resolves to the address shown: check the recipient."
        },
    )
}

/**
 * The recipient [found] as the form shows it: in full, with the name it was resolved from and its
 * name among [accounts], and [note] said of its address (the account itself, the token's contract).
 */
internal fun recipientItem(found: RecipientLookup.Found, accounts: List<Account>, note: (Address) -> String?) =
    RecipientItem(
        address = Eip55.format(found.address),
        name = found.name,
        resolvedAt = found.blockNumber?.let { "Resolved at finalized block ${grouped(it)}" },
        label = accounts.firstOrNull { it.address == found.address }?.label,
        note = note(found.address),
    )

/** What the recipient field says under [text], and whether that is an error. */
internal fun recipientNote(text: String, lookup: RecipientState?): Pair<String?, Boolean> = when (lookup) {
    null, RecipientState.Empty -> null to false
    is RecipientState.Resolving -> "Resolving the name with verified calls…" to false
    is RecipientState.Done -> when (val result = lookup.result) {
        is RecipientLookup.Found -> null to false
        RecipientLookup.NotAnAddress -> when {
            '.' in text -> NOT_A_NAME to true
            // Not while an address or a name is still being typed.
            text.length < ADDRESS_LENGTH && HEX_SO_FAR.matches(text) -> null to false
            !text.startsWith("0x", ignoreCase = true) && LABEL_SO_FAR.matches(text) ->
                "A name ends in its domain, as in name.eth." to false
            else -> NOT_AN_ADDRESS to true
        }
        RecipientLookup.ChecksumMismatch -> CHECKSUM to true
        RecipientLookup.ZeroAddress -> ZERO_ADDRESS to true
        RecipientLookup.OffchainOnly -> OFFCHAIN to true
        RecipientLookup.NoAddress -> "This name has no address." to true
        is RecipientLookup.Unavailable -> nameUnavailable(result.reason) to true
    }
}

/** An amount typed, in an asset's smallest units, or why it is none (both null while still being typed). */
internal class TypedAmount(val units: UInt256?, val error: String?)

/** [text] as an amount of [option], typed in its units. */
internal fun typedAmount(text: String, option: AssetOption?): TypedAmount {
    val typed = text.trim()
    if (typed.isEmpty() || option == null) return TypedAmount(null, null)
    val units = UInt256.parseUnits(typed, option.decimals)
    return when {
        units == null -> TypedAmount(null, amountError(typed, option))
        units.isZero -> TypedAmount(null, "Enter an amount above zero.")
        else -> TypedAmount(units, null)
    }
}

private fun amountError(typed: String, option: AssetOption): String? {
    val fraction = typed.substringAfter('.', missingDelimiterValue = "")
    return when {
        // "5." on its way to "5.5".
        option.decimals > 0 && typed.endsWith('.') &&
            UInt256.parseUnits(typed.dropLast(1), option.decimals) != null -> null
        ',' in typed -> "Use a dot for the decimals: 0.5, not 0,5."
        DECIMAL.matches(typed) && fraction.length > option.decimals -> if (option.decimals == 0) {
            "${option.symbol} has no decimals: whole numbers only."
        } else {
            "${option.symbol} has ${option.decimals} decimals: at most ${option.decimals} digits after the dot."
        }
        DECIMAL.matches(typed) -> "That is more than any balance can be."
        else -> "Not an amount: digits, with a dot for the decimals (0.5)."
    }
}

/**
 * What [amount] of [option] is worth in [currency], as a hint beside the amount: the verified
 * balance's value scaled to the amount — the same price, display only, never part of the amount
 * (CLAUDE.md, *Data sources*).
 */
internal fun amountHint(amount: UInt256?, option: AssetOption?, currency: DisplayCurrency, network: Network): String? {
    amount ?: return null
    val valued = option?.valuation as? Valuation.Valued ?: return null
    val balance = option.balance?.takeUnless { it.isZero } ?: return null
    val share = amount.toDecimal().toDouble() / balance.toDecimal().toDouble()
    return valuationText(valued.copy(amount = valued.amount * share), currency, network)?.let { "≈ $it" }
}

/** Why a name could not be resolved with verified calls, display-ready. */
internal fun nameUnavailable(reason: ReadUnavailable): String = when (reason) {
    ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so the name cannot be resolved."
    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the name cannot be resolved."
    ReadUnavailable.UNPROVEN -> "The name could not be resolved with verified calls this time."
    ReadUnavailable.REFUSED -> "The light client on this device cannot resolve names."
}

/** [units] in whole units with [decimals], exact: what a review shows and what is signed. */
internal fun exactAmount(units: UInt256, decimals: Int, symbol: String) = "${units.formatUnits(decimals)} $symbol"

private fun block(number: Long) = "block ${grouped(number)}"

/** Decimal places of the network's currency. */
internal const val NATIVE_DECIMALS = 18

/** What a balance not read yet shows. */
internal const val CHECKING = "Checking…"

/** What the zero address as a recipient says. */
internal const val ZERO_ADDRESS =
    "The zero address: nobody holds its key, so whatever is sent to it is lost. It is refused."

private const val ADDRESS_LENGTH = 42

/** How long the typing must pause before a name is resolved. */
private const val NAME_PAUSE_MS = 600L
private val HEX_SO_FAR = Regex("^(0|0[xX][0-9a-fA-F]*)$")
private val LABEL_SO_FAR = Regex("^[A-Za-z0-9-]+$")
private val DECIMAL = Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$")

private const val NOT_AN_ADDRESS = "Not an address: an address is 0x and 40 hexadecimal digits."
private const val NOT_A_NAME =
    "Not an ENS name Serotine can resolve: for now, names of letters (a–z), digits and hyphens."
private const val CHECKSUM = "The capitals do not match this address's checksum: check it for a typo."
private const val OFFCHAIN =
    "Only an off-chain gateway gives this name's address, and Serotine asks none: it cannot be resolved here."
