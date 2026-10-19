package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwapInputsTest {

    @Test
    fun aRowWithoutAContractPicksTheNetworksCurrencyAndAnAddressItsToken() {
        val usdc = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
        assertEquals(SwapPick.Native, SwapPick.of(null))
        assertEquals(SwapPick.Token(Address.parse(usdc)!!), SwapPick.of(usdc))
        assertNull(SwapPick.of("not an address"))
    }

    @Test
    fun aToleranceIsRefusedOnlyOnceSomethingTypedIsNone() {
        assertNull(toleranceError(""))
        assertNull(toleranceError(" 0.5 "))
        assertEquals(TOLERANCE_ERROR, toleranceError("6"))
        assertEquals(TOLERANCE_ERROR, toleranceError("0.555"))
    }

    @Test
    fun theSaleIsHeldBackWhileItsTokenIsReadOrWhenItIsNotHeld() {
        assertEquals("Checking this Safe's tokens…", sellNote(null, tokensRead = false, holder = "this Safe"))
        assertEquals(
            "The token asked for is not among this account's tokens: choose another.",
            sellNote(null, tokensRead = true, holder = "this account"),
        )
    }
}
