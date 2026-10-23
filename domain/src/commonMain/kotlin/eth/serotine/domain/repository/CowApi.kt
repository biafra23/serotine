package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256

/**
 * The port for CoW Protocol's order API (`api.cow.fi`), an off-chain service, used only for a
 * swap the user sends to CoW (owner ruling, 2026-10-22; CLAUDE.md, *Data sources*). Nothing it
 * answers is chain data, and nothing it answers is trusted:
 *
 * - its quote is an estimate, shown as not verified, and the signed minimum never falls below
 *   the verified Uniswap floor ([eth.serotine.domain.model.CowMinimum]);
 * - the order is built, hashed and signed here, and the API only carries it;
 * - an order's fill is read from the chain, never from here ([life] says only how the order
 *   stands in CoW's order book).
 *
 * The app data sent with each call is the pinned empty document
 * ([eth.serotine.domain.model.CowAppData]). :data implements it over HTTP. Never throws.
 */
interface CowApi {

    /**
     * CoW's estimate on [network] for selling exactly [sellAmount] of [sellToken] for [buyToken],
     * from [from]: what an order may buy, net of the fee CoW takes out of the trade.
     */
    suspend fun quote(
        network: Network,
        sellToken: Address,
        buyToken: Address,
        sellAmount: UInt256,
        from: Address,
    ): CowQuoteReading

    /**
     * Hands [order], signed by [owner] with [signature] (EIP-712), to CoW's order book on
     * [network]; [uid] is its UID as computed here, which CoW's answer must name, or the answer is
     * no acceptance ([CowPlacement.Unclear]). Whatever this answers, the order was signed and may
     * have reached CoW: it stays live until the chain proves it can no longer fill (docs/swaps.md,
     * *A signed order is live until it is proven dead*). The request is made once, never retried
     * by the client: an answer is the one that happened. Never [CowPlacement.Attempted].
     */
    suspend fun place(
        network: Network,
        order: CowOrder,
        owner: Address,
        signature: Signature,
        uid: CowOrderUid,
    ): CowPlacement

    /** How the order [uid] stands in CoW's order book on [network]: CoW's own report. */
    suspend fun life(network: Network, uid: CowOrderUid): CowOrderLife
}

/** What [CowApi.quote] answers. */
sealed interface CowQuoteReading {

    /**
     * CoW's estimate: an order selling the whole amount may buy [buyAmount], net of the fee,
     * [feeAmount] of the sold token, CoW takes out of the trade. Not verified.
     */
    data class Quoted(val buyAmount: UInt256, val feeAmount: UInt256) : CowQuoteReading

    /** CoW refused to quote, saying why ([why]; [errorType] is CoW's own word for it). */
    data class Refused(val why: CowRefusal, val errorType: String) : CowQuoteReading

    /** No answer that could be used ([why]). */
    data class Unavailable(val why: CowUnavailable) : CowQuoteReading
}

/** Why CoW refused a quote or an order, in the kinds the screens tell apart. */
enum class CowRefusal {
    /** No liquidity for the pair, or not enough of it for the amount. */
    NO_LIQUIDITY,

    /** CoW does not trade one of the tokens, or not right now. */
    UNSUPPORTED_TOKEN,

    /** The amount does not cover CoW's fee. */
    TOO_SMALL,

    /** CoW does not serve this account (its deny-list). */
    FORBIDDEN,

    /** The account's balance or allowance does not cover the order, by CoW's own check. */
    FUNDS,

    /** Any other refusal; its error type says which. */
    OTHER,
}

/** Why there is no usable answer from CoW's API. */
enum class CowUnavailable {
    /** No answer in time. */
    TIMEOUT,

    /** No connection could be made. */
    NO_CONNECTION,

    /** An HTTP error that is no refusal of CoW's (a server error, a gateway's). */
    SERVER_ERROR,

    /** An answer this version cannot decode or check: CoW's API may have changed. */
    UNDECODABLE,

    /** No API is pinned for the network. */
    NOT_PINNED,
}

/** How an order's POST to CoW stands. The order is live in every case (see [CowApi.place]). */
sealed interface CowPlacement {

    /**
     * The POST is made, or about to be, and no answer is recorded: CoW may hold the order. What
     * an order is recorded with before it is sent, so that a record never reads as unsent once a
     * request may have left.
     */
    data object Attempted : CowPlacement

    /** CoW took the order under [uid], the UID computed here. */
    data class Accepted(val uid: CowOrderUid) : CowPlacement

    /** CoW refused the order ([why]; [errorType] is CoW's own word for it). */
    data class Refused(val why: CowRefusal, val errorType: String) : CowPlacement

    /**
     * No clear answer ([why]): CoW may or may not hold the order. An answer naming another UID
     * than the one computed here is one too ([CowUnavailable.UNDECODABLE]).
     */
    data class Unclear(val why: CowUnavailable) : CowPlacement
}

/** What [CowApi.life] answers: how CoW's order book reports an order. Never a fill. */
sealed interface CowOrderLife {

    /** In the order book, open to the auction. */
    data object Open : CowOrderLife

    /** Past its validity in the order book. */
    data object Expired : CowOrderLife

    /** Cancelled in the order book. */
    data object Cancelled : CowOrderLife

    /** No longer open, by CoW's report; whether it filled is the chain's to say. */
    data object Closed : CowOrderLife

    /** The order book does not know the order. */
    data object Unknown : CowOrderLife

    /** No usable answer ([why]). */
    data class Unavailable(val why: CowUnavailable) : CowOrderLife
}
