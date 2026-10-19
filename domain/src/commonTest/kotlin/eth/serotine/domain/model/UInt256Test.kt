package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UInt256Test {

    // The decimals below are checked against Python's arbitrary-precision integers.
    private val max = "115792089237316195423570985008687907853269984665640564039457584007913129639935" // 2^256 - 1
    private val twoTo256 = "115792089237316195423570985008687907853269984665640564039457584007913129639936"
    private val twoTo128Minus1 = "340282366920938463463374607431768211455"
    private val twoTo128 = "340282366920938463463374607431768211456"
    private val twoTo128Plus1 = "340282366920938463463374607431768211457"

    // 2^256 - 1 and 2^256 in units of 18 decimals.
    private val maxIn18 = "115792089237316195423570985008687907853269984665640564039457.584007913129639935"
    private val twoTo256In18 = "115792089237316195423570985008687907853269984665640564039457.584007913129639936"

    private fun u(decimal: String) = UInt256.of(decimal)

    @Test
    fun decimalsRoundTrip() {
        listOf("0", "1", "9", "10", "4294967295", "4294967296", "18446744073709551616", "1000000000000000000", max)
            .forEach { assertEquals(it, u(it).toDecimal(), it) }
        assertEquals("9223372036854775807", UInt256.of(Long.MAX_VALUE).toDecimal())
        assertEquals("0", UInt256.ZERO.toString())
        assertEquals("1", UInt256.ONE.toString())
    }

    @Test
    fun parseTakesTheCanonicalDecimalOnly() {
        listOf("", "-1", "+1", "01", "00", "1.5", "0x10", " 1", "1 ", "1e18", "1_000", "1,000", "١", twoTo256)
            .forEach { assertNull(UInt256.parse(it), it) }
        assertFailsWith<IllegalArgumentException> { UInt256.of("abc") }
        assertFailsWith<IllegalArgumentException> { UInt256.of(-1) }
    }

    @Test
    fun bytesRoundTripMinimallyAndAsAWord() {
        val maxBytes = ByteArray(32) { 0xff.toByte() }
        assertEquals(u(max), UInt256.fromBigEndian(maxBytes))
        assertContentEquals(maxBytes, u(max).toBigEndian())
        assertContentEquals(maxBytes, u(max).toBytes32())

        assertContentEquals(ByteArray(0), UInt256.ZERO.toBigEndian())
        assertContentEquals(ByteArray(32), UInt256.ZERO.toBytes32())
        assertEquals(UInt256.ZERO, UInt256.fromBigEndian(ByteArray(0)))

        // 1024: leading zeros in, none out; an ABI word pads them back.
        val leadingZeros = byteArrayOf(0, 0, 0x04, 0x00)
        assertEquals(UInt256.of(1024), UInt256.fromBigEndian(leadingZeros))
        assertEquals("0400", UInt256.fromBigEndian(leadingZeros).toBigEndian().toHex())
        assertEquals("00".repeat(30) + "0400", UInt256.of(1024).toBytes32().toHex())

        val odd = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        assertEquals(odd, UInt256.fromBigEndian(odd.hexToBytes()).toBigEndian().toHex())
        assertFailsWith<IllegalArgumentException> { UInt256.fromBigEndian(ByteArray(33)) }
    }

    @Test
    fun equalityAndOrderAreByValue() {
        assertEquals(UInt256.of(5), u("5"))
        assertEquals(UInt256.of(5), UInt256.fromBigEndian(byteArrayOf(0, 0, 5)))
        assertEquals(UInt256.of(5).hashCode(), u("5").hashCode())
        assertTrue(u("4294967296") > u("4294967295"))
        assertTrue(u(max) > u(twoTo128Minus1))
        assertTrue(UInt256.ZERO < UInt256.ONE)
        val shuffled = listOf(max, "0", "18446744073709551616", "4294967296", "1", "4294967295").map(::u)
        assertEquals(
            listOf("0", "1", "4294967295", "4294967296", "18446744073709551616", max),
            shuffled.sorted().map { it.toDecimal() },
        )
        assertTrue(UInt256.ZERO.isZero)
        assertFalse(UInt256.ONE.isZero)
    }

    @Test
    fun additionCarriesAcrossLimbsAndRefusesOverflow() {
        assertEquals(u("4294967296"), u("4294967295") + UInt256.ONE)
        assertEquals(u("18446744073709551616"), u("18446744073709551615") + UInt256.ONE)
        assertEquals(u(max), u(twoTo128Minus1).plusOrNull(u(max) - u(twoTo128Minus1)))
        assertNull(u(max).plusOrNull(UInt256.ONE))
        assertFailsWith<ArithmeticException> { u(max) + UInt256.ONE }
    }

    @Test
    fun subtractionBorrowsAcrossLimbsAndRefusesUnderflow() {
        assertEquals(u(twoTo128Minus1), u(twoTo128) - UInt256.ONE)
        assertEquals(u("4294967295"), u("4294967296") - UInt256.ONE)
        assertEquals(UInt256.ZERO, u(max) - u(max))
        assertNull(UInt256.ZERO.minusOrNull(UInt256.ONE))
        assertNull(u(twoTo128Minus1).minusOrNull(u(max)))
        assertFailsWith<ArithmeticException> { UInt256.ONE - UInt256.of(2) }
    }

    @Test
    fun multiplicationIsExactUpToTheTopAndRefusesOverflow() {
        // (2^128 - 1)(2^128 + 1) = 2^256 - 1: every limb of both factors in play, nothing to spare.
        assertEquals(u(max), u(twoTo128Minus1) * u(twoTo128Plus1))
        // (2^128 - 1)^2 = 2^256 - 2^129 + 1.
        assertEquals(
            u("115792089237316195423570985008687907852589419931798687112530834793049593217025"),
            u(twoTo128Minus1) * u(twoTo128Minus1),
        )
        assertEquals(u("1" + "0".repeat(36)), u("1000000000000000000") * u("1000000000000000000"))
        assertEquals(u(max), u(max) * UInt256.ONE)
        assertEquals(UInt256.ZERO, u(max) * UInt256.ZERO)
        assertNull(u(max).timesOrNull(UInt256.of(2)))
        assertNull(u(twoTo128).timesOrNull(u(twoTo128)))
        assertFailsWith<ArithmeticException> { u(twoTo128Plus1) * u(twoTo128Plus1) }
    }

    /** Operands of random sizes; the sums, products and differences are Python's (null: out of range). */
    private class Row(val a: String, val b: String, val sum: String?, val product: String?, val difference: String?)

    @Test
    fun agreesWithArbitraryPrecisionOnRandomOperands() {
        val rows = listOf(
            Row(
                "103312099754129791299998669997518232836830326797011025302666955854516147083241",
                "109110187197469501209577030166792972399391083411738970941759427526226881509794",
                null,
                null,
                null,
            ),
            Row(
                "56662945938869628095179335989090305395225407513831183796089966213017197316716",
                "1",
                "56662945938869628095179335989090305395225407513831183796089966213017197316717",
                "56662945938869628095179335989090305395225407513831183796089966213017197316716",
                "56662945938869628095179335989090305395225407513831183796089966213017197316715",
            ),
            Row(
                "1102943538990864472469661474144025559408368175708777450362513",
                "54245575090036139",
                "1102943538990864472469661474144025559408368229954352540398652",
                "59829806564399140841921850093730413053779982789468260173240386170879820857307",
                "1102943538990864472469661474144025559408368121463202360326374",
            ),
            Row(
                "940383692160856318286080750931782160254",
                "42904334216694551718571592491835069131",
                "983288026377550870004652343423617229385",
                "40346536220398583822008302984841473849372054396049431814497375821558610519274",
                "897479357944161766567509158439947091123",
            ),
            Row(
                "612113250340183525296117552213411096378",
                "292822677873288167417790909170039994305",
                "904935928213471692713908461383451090683",
                null,
                "319290572466895357878326643043371102073",
            ),
            Row(
                "16262567928783029963",
                "15541602074888973224",
                "31804170003672003187",
                "252746359464997210211134195804696710712",
                "720965853894056739",
            ),
            Row(
                "76871689997569088633184230271",
                "5923514588",
                "76871689997569088639107744859",
                "455350577104814181056531768901819693348",
                "76871689997569088627260715683",
            ),
            Row(
                "95063208070704002861121768057528785966567875309896964433820866669415844067950",
                "237",
                "95063208070704002861121768057528785966567875309896964433820866669415844068187",
                null,
                "95063208070704002861121768057528785966567875309896964433820866669415844067713",
            ),
        )
        rows.forEach { row ->
            val (a, b) = u(row.a) to u(row.b)
            assertEquals(row.sum, a.plusOrNull(b)?.toDecimal(), "${row.a} + ${row.b}")
            assertEquals(row.sum, b.plusOrNull(a)?.toDecimal(), "${row.b} + ${row.a}")
            assertEquals(row.product, a.timesOrNull(b)?.toDecimal(), "${row.a} × ${row.b}")
            assertEquals(row.product, b.timesOrNull(a)?.toDecimal(), "${row.b} × ${row.a}")
            assertEquals(row.difference, a.minusOrNull(b)?.toDecimal(), "${row.a} - ${row.b}")
            row.sum?.let { assertEquals(a, u(it) - b) }
            assertEquals(a, UInt256.fromBigEndian(a.toBigEndian()))
            assertEquals(a, UInt256.fromBigEndian(a.toBytes32()))
        }
    }

    @Test
    fun parseUnitsScalesWholeUnitsToTheSmallest() {
        assertEquals(u("1500000000000000000"), UInt256.parseUnits("1.5", 18))
        assertEquals(u("1000000000000000000"), UInt256.parseUnits("1", 18))
        assertEquals(UInt256.ONE, UInt256.parseUnits("0.000000000000000001", 18))
        assertEquals(u("100000000000000000"), UInt256.parseUnits("0.1", 18))
        assertEquals(UInt256.ZERO, UInt256.parseUnits("0", 18))
        assertEquals(UInt256.ZERO, UInt256.parseUnits("0.0", 18))
        assertEquals(u("1500000"), UInt256.parseUnits("1.50", 6))
        assertEquals(u("1234567"), UInt256.parseUnits("1.234567", 6))
        assertEquals(UInt256.of(5), UInt256.parseUnits("5", 0))
        assertEquals(u(max), UInt256.parseUnits(max, 0))
        assertEquals(u(max), UInt256.parseUnits(maxIn18, 18))
        assertEquals(u("1" + "0".repeat(77)), UInt256.parseUnits("1", 77))
        // Zero takes any decimals; one more step than 2^256 holds does not.
        assertEquals(UInt256.ZERO, UInt256.parseUnits("0", 1_000_000))
        assertNull(UInt256.parseUnits("1", 78))
        assertNull(UInt256.parseUnits(twoTo256, 0))
        assertNull(UInt256.parseUnits(twoTo256In18, 18))
        assertFailsWith<IllegalArgumentException> { UInt256.parseUnits("1", -1) }
    }

    @Test
    fun parseUnitsRefusesWhatIsNotAPlainAmount() {
        listOf(
            "", ".", ".5", "5.", "-1", "+1", "1e18", "1E3", "1.2.3", "1..2", " 1", "1 ", "01", "01.5", "00.5",
            "1,5", "1_000", "abc", "0x1", "١", "1.٥", "NaN", "Infinity",
        ).forEach { assertNull(UInt256.parseUnits(it, 18), it) }
        // More fraction digits than the currency has, even zeros.
        assertNull(UInt256.parseUnits("0.0000000000000000001", 18))
        assertNull(UInt256.parseUnits("1.500", 2))
        assertNull(UInt256.parseUnits("5.0", 0))
    }

    @Test
    fun formatUnitsWritesWholeUnitsWithoutTrailingZeros() {
        assertEquals("1.5", u("1500000000000000000").formatUnits(18))
        assertEquals("1", u("1000000000000000000").formatUnits(18))
        assertEquals("0.000000000000000001", UInt256.ONE.formatUnits(18))
        assertEquals("0", UInt256.ZERO.formatUnits(18))
        assertEquals("0", UInt256.ZERO.formatUnits(0))
        assertEquals("5", UInt256.of(5).formatUnits(0))
        assertEquals("1.5", u("1500000").formatUnits(6))
        assertEquals("1.234567", u("1234567").formatUnits(6))
        assertEquals("1", UInt256.of(100).formatUnits(2))
        assertEquals("0.1", UInt256.of(10).formatUnits(2))
        assertEquals("10.01", UInt256.of(1001).formatUnits(2))
        assertEquals(maxIn18, u(max).formatUnits(18))
        assertEquals(max.take(1) + "." + max.drop(1), u(max).formatUnits(77))
        assertEquals("0." + "0".repeat(77) + "1", UInt256.ONE.formatUnits(78))
        assertFailsWith<IllegalArgumentException> { UInt256.ONE.formatUnits(-1) }
    }

    @Test
    fun formatAndParseUnitsAreInverses() {
        listOf(0 to "0", 0 to max, 6 to "1", 6 to "1234567", 18 to "1500000000000000000", 18 to max, 77 to max)
            .forEach { (decimals, decimal) ->
                val formatted = u(decimal).formatUnits(decimals)
                assertEquals(u(decimal), UInt256.parseUnits(formatted, decimals), "$decimal/$decimals")
            }
    }

    @Test
    fun convertsToAndFromWei() {
        assertEquals(UInt256.of(1000), Wei.of("1000").toUInt256OrNull())
        assertEquals(UInt256.ZERO, Wei.of("0").toUInt256OrNull())
        assertEquals(u(max), Wei.of(max).toUInt256OrNull())
        assertNull(Wei.of(twoTo256).toUInt256OrNull())
        assertEquals(Wei.of(max), u(max).toWei())
        assertEquals(Wei.of("0"), UInt256.ZERO.toWei())
    }

    @Test
    fun convertsToALongWhenItFitsOne() {
        assertEquals(0L, UInt256.ZERO.toLongOrNull())
        assertEquals(4_294_967_296L, UInt256.of(4_294_967_296L).toLongOrNull())
        assertEquals(Long.MAX_VALUE, UInt256.of(Long.MAX_VALUE).toLongOrNull())
        assertNull(u("9223372036854775808").toLongOrNull(), "2^63")
        assertNull(u("18446744073709551616").toLongOrNull(), "2^64")
        assertNull(u(max).toLongOrNull())
    }

    @Test
    fun multipliesAndDividesThroughAProductAbove256Bits() {
        // (2^256 - 1)^2 / (2^256 - 1): the product needs 512 bits, the quotient does not.
        assertEquals(u(max), u(max).mulDivOrNull(u(max), u(max)))
        // 2^200 × 2^200 / 2^150 = 2^250.
        val twoTo200 = u("1606938044258990275541962092341162602522202993782792835301376")
        assertEquals(
            u("1809251394333065553493296640760748560207343510400633813116524750123642650624"),
            twoTo200.mulDivOrNull(twoTo200, u("1427247692705959881058285969449495136382746624")),
        )
        // Rounded down.
        assertEquals(
            u("1741894730528882788945500467623184619031963768587303764670"),
            u("123456789012345678901234567890").mulDivOrNull(u("98765432109876543210987654321"), UInt256.of(7)),
        )
        assertEquals(UInt256.ZERO, UInt256.of(5).mulDivOrNull(UInt256.ONE, UInt256.of(6)))
    }

    @Test
    fun refusesAZeroDivisorAndAQuotientAbove256Bits() {
        assertNull(UInt256.ONE.mulDivOrNull(UInt256.ONE, UInt256.ZERO))
        // 2^255 × 4 / 2 = 2^256.
        val twoTo255 = u("57896044618658097711785492504343953926634992332820282019728792003956564819968")
        assertNull(twoTo255.mulDivOrNull(UInt256.of(4), UInt256.of(2)))
    }
}
