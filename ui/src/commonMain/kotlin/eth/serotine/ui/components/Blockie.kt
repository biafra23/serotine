package eth.serotine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The blockie of an address: the ethereum/blockies identicon every address on screen carries
 * (docs/wallet-ux.md), drawn as a circle. Seeded with the lower-case address, as the
 * reference and the wallets that use it are, so the same address looks the same everywhere.
 */
@Composable
fun Blockie(address: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val pattern = remember(address) { BlockiePattern.of(address.lowercase()) }
    Canvas(modifier.size(size).clip(CircleShape)) {
        val cell = this.size.width / BlockiePattern.SIZE
        drawRect(pattern.background.toColor())
        pattern.cells.forEachIndexed { i, value ->
            if (value == 0) return@forEachIndexed
            drawRect(
                color = (if (value == 1) pattern.color else pattern.spot).toColor(),
                topLeft = Offset((i % BlockiePattern.SIZE) * cell, (i / BlockiePattern.SIZE) * cell),
                // A hair wider, so neighbouring cells leave no anti-aliased seam between them.
                size = Size(cell + 0.5f, cell + 0.5f),
            )
        }
    }
}

/**
 * A colour as the reference computes it: [hue] in whole degrees, [saturation] and
 * [lightness] in percent, both below 100 by the generator's construction. [toColor] clamps
 * them anyway, as CSS would, so a colour can never make [Color.hsl] throw.
 */
data class Hsl(val hue: Int, val saturation: Double, val lightness: Double) {
    fun toColor(): Color = Color.hsl(
        hue.toFloat(),
        (saturation / 100).coerceIn(0.0, 1.0).toFloat(),
        (lightness / 100).coerceIn(0.0, 1.0).toFloat(),
    )
}

/**
 * The 8×8 blockie of a seed: a port of ethereum/blockies (`blockies.js`), producing the same
 * colours and cells from the same seed. [cells] holds row by row 0 (background), 1
 * ([color]) or 2 ([spot]); the right half mirrors the left.
 */
class BlockiePattern private constructor(
    val color: Hsl,
    val background: Hsl,
    val spot: Hsl,
    val cells: List<Int>,
) {
    companion object {
        /** Cells per side; the reference's default size. */
        const val SIZE = 8

        /** The pattern of [seed], which for an address is its lower-case hex. */
        fun of(seed: String): BlockiePattern {
            val random = Xorshift(seed)
            // The reference's order: the colour, the background, the spot colour, then the cells.
            val color = random.color()
            val background = random.color()
            val spot = random.color()
            val half = (SIZE + 1) / 2
            val cells = (0 until SIZE).flatMap {
                val row = List(half) { kotlin.math.floor(random.next() * 2.3).toInt() }
                row + row.take(SIZE - half).reversed()
            }
            return BlockiePattern(color, background, spot, cells)
        }
    }
}

/**
 * The reference's xorshift generator, with JavaScript's integer semantics kept exactly:
 * seeding mixes the seed's UTF-16 code units into four 32-bit words as `(w << 5) - w + c`
 * (the shift on the word truncated to 32 bits, the subtraction on its full value), and
 * [next] returns the word as unsigned over 2^31. Its sign bit is always clear (each
 * operand of the final XOR has its own sign bit cancelled), so that is in [0, 1).
 */
private class Xorshift(seed: String) {
    private val words = IntArray(4)

    init {
        val mixed = LongArray(4)
        seed.forEachIndexed { i, char ->
            val w = mixed[i % 4]
            mixed[i % 4] = (w.toInt() shl 5).toLong() - w + char.code
        }
        for (i in 0 until 4) words[i] = mixed[i].toInt()
    }

    fun next(): Double {
        val t = words[0] xor (words[0] shl 11)
        words[0] = words[1]
        words[1] = words[2]
        words[2] = words[3]
        words[3] = words[3] xor (words[3] shr 19) xor t xor (t shr 8)
        return (words[3].toLong() and 0xffff_ffffL).toDouble() / 2_147_483_648.0
    }

    fun color(): Hsl {
        val hue = kotlin.math.floor(next() * 360).toInt()
        val saturation = next() * 60 + 40
        val lightness = (next() + next() + next() + next()) * 25
        return Hsl(hue, saturation, lightness)
    }
}
