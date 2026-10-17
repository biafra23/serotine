package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The vectors are ethereum/tests' RLPTests/rlptest.json (commit c67e485f, named by their keys
 * there) and the examples of ethereum.org's RLP page, which repeats the yellow paper's.
 */
class RlpTest {

    private fun string(text: String) = Rlp.encodeString(text.encodeToByteArray())

    private fun list(vararg items: ByteArray) = Rlp.encodeList(items.toList())

    private fun strings(vararg texts: String) = Rlp.encodeList(texts.map(::string))

    @Test
    fun stringsUpToFiftyFiveBytesCarryTheirLengthInThePrefix() {
        assertEquals("80", string("").toHex()) // emptystring
        assertEquals("83646f67", string("dog").toHex()) // shortstring
        // shortstring2: 55 bytes, the longest a one-byte prefix holds.
        assertEquals(
            "b74c6f72656d20697073756d20646f6c6f722073697420616d65742c20" +
                "636f6e7365637465747572206164697069736963696e6720656c69",
            string("Lorem ipsum dolor sit amet, consectetur adipisicing eli").toHex(),
        )
    }

    @Test
    fun aSingleByteBelowEightyIsItsOwnEncoding() {
        assertEquals("00", Rlp.encodeString(byteArrayOf(0x00)).toHex()) // bytestring00
        assertEquals("01", Rlp.encodeString(byteArrayOf(0x01)).toHex()) // bytestring01
        assertEquals("7f", Rlp.encodeString(byteArrayOf(0x7f)).toHex()) // bytestring7F
        assertEquals("0f", Rlp.encodeString(byteArrayOf(0x0f)).toHex()) // ethereum.org: '\x0f'
        assertEquals("8180", Rlp.encodeString(byteArrayOf(0x80.toByte())).toHex())
    }

    @Test
    fun longerStringsCarryTheLengthOfTheirLength() {
        // longstring: 56 bytes, the first that needs a length byte.
        assertEquals(
            "b8384c6f72656d20697073756d20646f6c6f722073697420616d65742c20" +
                "636f6e7365637465747572206164697069736963696e6720656c6974",
            string("Lorem ipsum dolor sit amet, consectetur adipisicing elit").toHex(),
        )
        // longstring2 is 1024 bytes of Lorem ipsum, 0xb90400 + the bytes: two length bytes. Any
        // 1024 bytes take the same prefix, so these stand in for that text.
        val kilobyte = ByteArray(1024) { (it % 251).toByte() }
        assertEquals("b90400" + kilobyte.toHex(), Rlp.encodeString(kilobyte).toHex())
    }

    @Test
    fun integersAreTheirMinimalBigEndianBytes() {
        val cases = listOf(
            "0" to "80", // zero
            "1" to "01", // smallint
            "16" to "10", // smallint2
            "79" to "4f", // smallint3
            "127" to "7f", // smallint4
            "128" to "8180", // mediumint1
            "1000" to "8203e8", // mediumint2
            "1024" to "820400", // ethereum.org
            "100000" to "830186a0", // mediumint3
            "83729609699884896815286331701780722" to "8f102030405060708090a0b0c0d0e0f2", // mediumint4
            // mediumint5
            "105315505618206987246253880190783558935785933862974822347068935681" to
                "9c0100020003000400050006000700080009000a000b000c000d000e01",
        )
        cases.forEach { (decimal, encoded) ->
            assertEquals(encoded, Rlp.encodeUInt(UInt256.of(decimal)).toHex(), decimal)
        }
        assertEquals("830186a0", Rlp.encodeUInt(100_000L).toHex())
        assertEquals("80", Rlp.encodeUInt(0L).toHex())
    }

    @Test
    fun listsUpToFiftyFiveBytesCarryTheirLengthInThePrefix() {
        assertEquals("c0", list().toHex()) // emptylist
        assertEquals("c88363617483646f67", strings("cat", "dog").toHex()) // ethereum.org
        assertEquals("cc83646f6783676f6483636174", strings("dog", "god", "cat").toHex()) // stringlist
        // multilist: ["zw", [4], 1]
        assertEquals("c6827a77c10401", list(string("zw"), list(Rlp.encodeUInt(4L)), Rlp.encodeUInt(1L)).toHex())
        // shortListMax1: a 55-byte payload.
        assertEquals(
            "f784617364668471776572847a78637684617364668471776572847a78637684617364668471776572847a786376" +
                "84617364668471776572",
            strings("asdf", "qwer", "zxcv", "asdf", "qwer", "zxcv", "asdf", "qwer", "zxcv", "asdf", "qwer").toHex(),
        )
        // dictTest1
        assertEquals(
            "ecca846b6579318476616c31ca846b6579328476616c32ca846b6579338476616c33ca846b6579348476616c34",
            list(
                strings("key1", "val1"),
                strings("key2", "val2"),
                strings("key3", "val3"),
                strings("key4", "val4"),
            ).toHex(),
        )
    }

    @Test
    fun nestedEmptyListsSpellTheSetTheoreticalNumbers() {
        val zero = list()
        val one = list(zero)
        val two = list(zero, one)
        assertEquals("c4c2c0c0c0", list(list(zero, zero), zero).toHex()) // listsoflists
        // listsoflists2, ethereum.org's "set theoretical representation of three": [ [], [[]], [ [], [[]] ] ].
        assertEquals("c7c0c1c0c3c0c1c0", list(zero, one, two).toHex())
    }

    @Test
    fun longerListsCarryTheLengthOfTheirLength() {
        val asdfQwerZxcv = strings("asdf", "qwer", "zxcv")
        val inner = "cf84617364668471776572847a786376"
        // longList1: a 64-byte payload, one length byte.
        assertEquals("f840" + inner.repeat(4), Rlp.encodeList(List(4) { asdfQwerZxcv }).toHex())
        // longList2: thirty-two of them, a 512-byte payload, two length bytes.
        assertEquals("f90200" + inner.repeat(32), Rlp.encodeList(List(32) { asdfQwerZxcv }).toHex())
    }
}
