package eth.serotine.ui.ratewidget

import eth.serotine.ui.components.NetworkLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/** [widgetNetworkUiState]: which networks a widget being placed may show. */
class WidgetNetworkUiStateTest {

    private val ethereum = NetworkLabel(1, "Ethereum", testnet = false)
    private val sepolia = NetworkLabel(11_155_111, "Sepolia", testnet = true)
    private val networks = listOf(ethereum, sepolia)

    @Test
    fun theNetworksThatAreOnWithAccountsAreOffered() {
        assertEquals(
            WidgetNetworkUiState(listOf(ethereum)),
            widgetNetworkUiState(networks, on = setOf(1, 11_155_111), withAccounts = setOf(1)),
        )
        // A network with accounts that is off reads nothing: not offered.
        assertEquals(
            WidgetNetworkUiState(listOf(sepolia)),
            widgetNetworkUiState(networks, on = setOf(11_155_111), withAccounts = setOf(1, 11_155_111)),
        )
    }

    @Test
    fun aWalletWithoutAccountsIsOfferedEveryNetworkThatIsOn() {
        assertEquals(
            WidgetNetworkUiState(listOf(ethereum, sepolia)),
            widgetNetworkUiState(networks, on = setOf(1, 11_155_111), withAccounts = emptySet()),
        )
        assertEquals(
            WidgetNetworkUiState(listOf(ethereum)),
            widgetNetworkUiState(networks, on = setOf(1), withAccounts = null),
        )
    }

    @Test
    fun accountsOnlyOnNetworksThatAreOffLeaveEveryNetworkThatIsOn() {
        // Sepolia's accounts with Sepolia off: Ethereum, on, can still show its rate.
        assertEquals(
            WidgetNetworkUiState(listOf(ethereum)),
            widgetNetworkUiState(networks, on = setOf(1), withAccounts = setOf(11_155_111)),
        )
    }

    @Test
    fun withNoNetworkOnNothingIsOfferedAndItSaysWhy() {
        assertEquals(
            WidgetNetworkUiState(emptyList(), NO_NETWORK_ON),
            widgetNetworkUiState(networks, on = emptySet(), withAccounts = setOf(1)),
        )
    }
}
