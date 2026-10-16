package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import kotlin.test.Test
import kotlin.test.assertEquals

/** EIP-55 against the examples in the EIP itself (ERC-55, "Test Cases"). */
class Eip55Test {

    private val checksummed = listOf(
        // All caps
        "0x52908400098527886E0F7030069857D2E4169EE7",
        "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
        // All lower
        "0xde709f2102306220921060314715629080e2fb77",
        "0x27b1fdb04752bbc536007a920d24acb045561c26",
        // Normal
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
    )

    @Test
    fun formatsEveryExampleAsTheEipDoes() {
        checksummed.forEach { expected -> assertEquals(expected, Eip55.format(Address.of(expected))) }
    }

    @Test
    fun acceptsTheExamplesAndAnySingleCaseSpelling() {
        checksummed.forEach { text ->
            val address = Address.of(text)
            assertEquals(AddressInput.Valid(address), Eip55.parse(text))
            assertEquals(AddressInput.Valid(address), Eip55.parse(text.lowercase()))
            assertEquals(AddressInput.Valid(address), Eip55.parse("0x" + text.drop(2).uppercase()))
            assertEquals(AddressInput.Valid(address), Eip55.parse("  $text\n"))
        }
    }

    @Test
    fun refusesAMixedCaseSpellingWhoseChecksumFails() {
        // One letter's case flipped.
        assertEquals(AddressInput.ChecksumMismatch, Eip55.parse("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
        assertEquals(AddressInput.ChecksumMismatch, Eip55.parse("0xFb6916095ca1df60bB79Ce92cE3Ea74c37c5d359"))
    }

    @Test
    fun refusesWhatIsNoAddress() {
        listOf(
            "",
            "0x",
            "5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", // no 0x
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAe", // a digit short
            "vitalik.eth",
        ).forEach { assertEquals(AddressInput.NotAnAddress, Eip55.parse(it)) }
    }

    @Test
    fun shortensForARow() {
        assertEquals("0x5aAe…eAed", shortAddress("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    }
}
