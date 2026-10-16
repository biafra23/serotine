package eth.serotine.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The blockie port against the reference: each vector is what ethereum/blockies' own
 * `buildOpts` and `createImageData` (blockies.js, master) return for the seed, the colours
 * as its CSS strings carry them.
 */
class BlockiePatternTest {

    private class Vector(val seed: String, val color: Hsl, val background: Hsl, val spot: Hsl, val cells: String)

    private val vectors = listOf(
        Vector(
            "0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2",
            color = Hsl(243, 54.39107585698366, 35.16435233177617),
            background = Hsl(14, 65.48953473567963, 55.20205913344398),
            spot = Hsl(164, 51.091849161311984, 53.15981227904558),
            cells = "0010010022100122100000010012210011100111101001011010010100111100",
        ),
        Vector(
            "0x5ad1e7c93b04f2a8e6d1c5b7a9f3e2d4c8b607be",
            color = Hsl(205, 96.06302723288536, 35.80893590115011),
            background = Hsl(246, 92.73769333958626, 30.438651505392045),
            spot = Hsl(126, 86.20356334373355, 33.75472290208563),
            cells = "2100001210100101010000100102201000022000011221100021120011011011",
        ),
        Vector(
            "0xd8da6bf26964af9d7eed9e03e53415d37aa96045",
            color = Hsl(240, 99.40706428140402, 49.26687970291823),
            background = Hsl(325, 48.64401903003454, 22.80696037923917),
            spot = Hsl(155, 95.38082413375378, 59.93704709690064),
            cells = "0111111012022021111221110100001001000010011001100000000002111120",
        ),
        Vector(
            "0x0000000000000000000000000000000000000000",
            color = Hsl(354, 66.81960879825056, 56.21398128569126),
            background = Hsl(213, 65.0781786441803, 49.646922980900854),
            spot = Hsl(327, 41.4509454369545, 63.29597851727158),
            cells = "0010010010000001011001102111111210122101100110012000000200211200",
        ),
        Vector(
            "0xffffffffffffffffffffffffffffffffffffffff",
            color = Hsl(231, 83.16503786481917, 45.04945760127157),
            background = Hsl(291, 89.97575571760535, 45.31843470176682),
            spot = Hsl(269, 40.146015379577875, 33.87761544436216),
            cells = "0012210011111111122002210020020011011011001001001101101111100111",
        ),
    )

    @Test
    fun matchesTheReferenceColourForColourAndCellForCell() {
        vectors.forEach { vector ->
            val pattern = BlockiePattern.of(vector.seed)
            assertEquals(vector.color, pattern.color, vector.seed)
            assertEquals(vector.background, pattern.background, vector.seed)
            assertEquals(vector.spot, pattern.spot, vector.seed)
            assertEquals(vector.cells, pattern.cells.joinToString(""), vector.seed)
        }
    }

    @Test
    fun eachRowMirrorsItsLeftHalf() {
        val cells = BlockiePattern.of(vectors.first().seed).cells
        cells.chunked(BlockiePattern.SIZE).forEach { row -> assertEquals(row, row.reversed()) }
    }

    @Test
    fun theGeneratorStaysWithinTheColourRanges() {
        repeat(2_000) { i ->
            val pattern = BlockiePattern.of("0x" + i.toString(16).padStart(40, '0'))
            listOf(pattern.color, pattern.background, pattern.spot).forEach { hsl ->
                assertTrue(
                    hsl.hue in 0 until 360 && hsl.saturation in 40.0..100.0 && hsl.lightness in 0.0..100.0,
                    "$hsl out of range",
                )
            }
        }
    }

    @Test
    fun aColourOutOfRangeIsClampedAsCssWould() {
        val color = Hsl(200, 130.0, 160.0).toColor()
        assertEquals(Hsl(200, 100.0, 100.0).toColor(), color)
    }
}
