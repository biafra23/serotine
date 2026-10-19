package eth.serotine.ui.accountdetail

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.Control
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The account detail screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. AccountDetailScreenPreviewsTest renders each one in accountDetailScreenPreviews,
// at the end. The addresses are EIP-55's own examples, and the amounts are made up.

private const val DAILY = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val SAVINGS = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val TREASURY = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"

private const val PRICES = "Prices: Chainlink feeds, read through the light client, testnet prices"

private val ether = AssetRow(
    "ETH",
    "Ether",
    "1.25 ETH",
    "eth",
    verified = true,
    verifiedAt = "block 8,412,307",
    value = "$3,125.00 · testnet prices",
)

private val usdc = AssetRow(
    "USDC",
    "USDC",
    "250 USDC",
    "usdc",
    verified = true,
    verifiedAt = "block 8,412,305",
    value = "$250.00 · testnet prices",
    contract = "0x27b1fdb04752bbc536007a920d24acb045561c26",
)

private val uniswap = AssetRow(
    "UNI",
    "Uniswap",
    "3 UNI",
    "uni",
    verified = true,
    verifiedAt = "block 8,412,306",
    value = "No price",
    contract = "0xde709f2102306220921060314715629080e2fb77",
)

private val daily = AccountDetailUiState.Ready(
    label = "Daily",
    address = DAILY,
    kind = AccountKind.EOA,
    control = Control.FULL_CONTROL,
    total = "$3,375.00",
    totalNote = "Testnet prices · 1 asset without a price",
    assets = listOf(ether, usdc, uniswap),
    pricesNote = PRICES,
)

private val treasury = AccountDetailUiState.Ready(
    label = "Treasury",
    address = TREASURY,
    kind = AccountKind.SAFE,
    control = Control.CO_SIGNER,
    total = "$1,650.00",
    totalNote = "Testnet prices",
    assets = listOf(
        ether.copy(amount = "0.5 ETH", value = "$1,250.00 · testnet prices"),
        usdc.copy(amount = "400 USDC", value = "$400.00 · testnet prices"),
    ),
    pricesNote = PRICES,
    safe = SafeDetails.Shown(
        title = "Safe 1.4.1 · nonce 12",
        signers = "2 of 3 owners must sign",
        owners = listOf(
            OwnerRow(DAILY, "Daily", heldHere = true),
            OwnerRow(SAVINGS, "Savings", heldHere = false),
            OwnerRow("0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb", null, heldHere = false),
        ),
        modules = listOf("0x52908400098527886E0F7030069857D2E4169EE7"),
        guard = "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
        checkedAt = "Verified at block 8,412,307",
    ),
    kindNote = "Safe",
    threshold = "2/3",
    queue = SafeQueue.Shown(
        rows = listOf(
            QueueRow(
                hash = "0x" + "3c".repeat(32),
                action = "Send 40 USDC",
                about = SAVINGS,
                nonce = "Nonce 12 · next",
                signatures = "1 of 2 signatures on this device",
            ),
            QueueRow(
                hash = "0x" + "4d".repeat(32),
                action = "Delegate call",
                about = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
                nonce = "Nonce 13 · 1 before it",
                signatures = "0 of 2 signatures on this device",
                refusal = "Not signed here: a delegate call",
            ),
        ),
    ),
)

@Composable
private fun AccountDetailPreview(state: AccountDetailUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        AccountDetailScreen(
            state = state,
            onBack = {},
            onSend = {},
            onReceive = {},
            onCopy = {},
            onRename = {},
            onRemove = {},
            onHideToken = { _, _ -> },
            onMessageShown = {},
            onSort = {},
            onSegment = {},
            onOpenSafeTx = {},
            onImportRequest = {},
        )
    }
}

@Preview
@Composable
private fun AccountDetailAssetsPreview() = AccountDetailPreview(daily)

@Preview
@Composable
private fun AccountDetailAssetsDarkPreview() = AccountDetailPreview(daily, darkTheme = true)

@Preview
@Composable
private fun AccountDetailCheckingPreview() = AccountDetailPreview(
    daily.copy(
        label = "Savings",
        address = SAVINGS,
        control = Control.WATCH_ONLY,
        total = "Checking…",
        totalNote = "Testnet prices · still checking",
        assets = listOf(ether.copy(amount = "Checking…", verified = false, verifiedAt = null, value = null)),
        tokensNote = "Checking 3 tokens",
    ),
)

private const val LAST_KNOWN = "Last verified Oct 19, 2026, 2:32 PM · may have changed since"

/** The verified head too old to read at: the amounts last verified, each said to be so. */
@Preview
@Composable
private fun AccountDetailHeadTooOldPreview() = AccountDetailPreview(
    daily.copy(
        total = "Checking…",
        totalNote = "Testnet prices · still checking",
        assets = listOf(ether, usdc, uniswap).map {
            it.copy(verified = false, verifiedAt = null, value = null, lastKnown = LAST_KNOWN)
        },
    ),
)

@Preview
@Composable
private fun AccountDetailByNameWithImpostorPreview() = AccountDetailPreview(
    daily.copy(
        assets = listOf(
            ether,
            usdc,
            AssetRow(
                "USDC",
                "USD Coin",
                "5000 USDC",
                null,
                verified = true,
                verifiedAt = "block 8,412,306",
                value = "No price",
                contract = "0xde709f2102306220921060314715629080e2fb77",
                warning = "Not the built-in USDC: another contract",
            ),
        ),
        sort = AssetSort.NAME,
    ),
)

@Preview
@Composable
private fun AccountDetailSafeAssetsPreview() = AccountDetailPreview(treasury)

@Preview
@Composable
private fun AccountDetailSafeOwnersPreview() = AccountDetailPreview(treasury.copy(segment = DetailSegment.OWNERS))

@Preview
@Composable
private fun AccountDetailSafeQueuePreview() = AccountDetailPreview(treasury.copy(segment = DetailSegment.QUEUE))

@Preview
@Composable
private fun AccountDetailSafeQueueDarkPreview() =
    AccountDetailPreview(treasury.copy(segment = DetailSegment.QUEUE), darkTheme = true)

@Preview
@Composable
private fun AccountDetailSafeQueueEmptyPreview() = AccountDetailPreview(
    treasury.copy(segment = DetailSegment.QUEUE, queue = SafeQueue.Shown(rows = emptyList())),
)

@Preview
@Composable
private fun AccountDetailSafeQueueUnverifiedPreview() = AccountDetailPreview(
    treasury.copy(
        segment = DetailSegment.QUEUE,
        queue = (treasury.queue as SafeQueue.Shown).copy(
            note = "The Safe cannot be verified right now, so neither its nonce nor these signatures are checked.",
        ),
    ),
)

@Preview
@Composable
private fun AccountDetailSafeQueueUnreadablePreview() = AccountDetailPreview(
    treasury.copy(
        segment = DetailSegment.QUEUE,
        queue = SafeQueue.Unavailable(
            "This device's queue of Safe transactions cannot be read right now. Serotine keeps trying.",
        ),
    ),
)

@Preview
@Composable
private fun AccountDetailSafeReadingPreview() = AccountDetailPreview(
    treasury.copy(
        control = Control.WATCH_ONLY,
        safe = SafeDetails.Checking,
        segment = DetailSegment.OWNERS,
        threshold = null,
    ),
)

@Preview
@Composable
private fun AccountDetailNotASafePreview() = AccountDetailPreview(
    treasury.copy(
        control = Control.WATCH_ONLY,
        safe = SafeDetails.Unavailable("This address no longer holds a recognised Safe: its owners are not shown."),
        segment = DetailSegment.OWNERS,
        kindNote = "Not a recognised Safe",
        threshold = null,
    ),
)

@Preview
@Composable
private fun AccountDetailLoadingPreview() = AccountDetailPreview(AccountDetailUiState.Loading)

@Preview
@Composable
private fun AccountDetailListUnavailablePreview() = AccountDetailPreview(
    AccountDetailUiState.Unavailable("Your account list cannot be read right now. Serotine keeps trying."),
)

/** Every preview above, which AccountDetailScreenPreviewsTest renders: a new preview goes in here too. */
internal val accountDetailScreenPreviews: List<@Composable () -> Unit> = listOf(
    { AccountDetailAssetsPreview() },
    { AccountDetailAssetsDarkPreview() },
    { AccountDetailCheckingPreview() },
    { AccountDetailHeadTooOldPreview() },
    { AccountDetailByNameWithImpostorPreview() },
    { AccountDetailSafeAssetsPreview() },
    { AccountDetailSafeOwnersPreview() },
    { AccountDetailSafeQueuePreview() },
    { AccountDetailSafeQueueDarkPreview() },
    { AccountDetailSafeQueueEmptyPreview() },
    { AccountDetailSafeQueueUnverifiedPreview() },
    { AccountDetailSafeQueueUnreadablePreview() },
    { AccountDetailSafeReadingPreview() },
    { AccountDetailNotASafePreview() },
    { AccountDetailLoadingPreview() },
    { AccountDetailListUnavailablePreview() },
)
