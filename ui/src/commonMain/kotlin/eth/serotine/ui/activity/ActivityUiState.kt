package eth.serotine.ui.activity

/**
 * Everything an account's Activity screen renders (docs/wallet-ux.md, screen 25; issue #85): the
 * transactions this device sent for it and, for a Safe, its own events on chain, newest first.
 * Display-ready.
 */
sealed interface ActivityUiState {

    /** The account's name, or its short address while it is not in the wallet. */
    val title: String

    /** Nothing read yet. */
    data class Loading(override val title: String) : ActivityUiState

    /**
     * The [rows], newest first (none: nothing listed yet, which [empty] says), and [coverage],
     * what the list covers and what it leaves out.
     */
    data class Shown(
        override val title: String,
        val rows: List<ActivityRow>,
        val coverage: String,
        val empty: String = "Nothing sent from this device yet.",
    ) : ActivityUiState

    /** The history cannot be read right now, for the reason [message] gives. */
    data class Unavailable(override val title: String, val message: String) : ActivityUiState
}

/**
 * One transaction sent from this device, or one event of a Safe on chain: its [key] in the list,
 * what it did ([title]: "Sent 0.5 ETH", "Received 0.1 ETH"), who or what else it concerns
 * ([detail]: "To Bob · 0x12…5678", "Treasury · Safe nonce 4"), the EIP-55 address its blockie is of
 * ([about]), when a peer took it or the block it is in ([time]), and how it ended as this device saw
 * it ([status], "In block 9,412,118", and its [state]). [opens] is the transaction hash (`0x` and 64
 * lower-case hex digits) whose status a tap opens: a transaction sent from here; null for an event,
 * which opens nothing yet.
 */
data class ActivityRow(
    val key: String,
    val title: String,
    val detail: String,
    val about: String,
    val time: String,
    val status: String,
    val state: ActivityState,
    val opens: String?,
)

/** How a sent transaction ended, as an [ActivityRow] marks it. */
enum class ActivityState {
    /** In a block, its receipt verified, and it succeeded (finalized or not yet). */
    DONE,

    /** No block seen to include it yet. */
    WAITING,

    /** In a block, and it reverted: its fee paid, nothing else done. */
    FAILED,

    /** No peer confirmed its broadcast, and no block seen to include it. */
    UNCONFIRMED,

    /** Its nonce was used while the app was not watching: by it, unless another took its place. */
    UNKNOWN,

    /** Done on chain, and what it did can move a Safe's funds without its owners. */
    WARNING,
}
