/*
 * The byte-mode path of the QR Code generator library by Project Nayuki, ported to Kotlin.
 *
 * Copyright (c) Project Nayuki. (MIT License)
 * https://www.nayuki.io/page/qr-code-generator-library
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 *   implied, including but not limited to the warranties of merchantability,
 *   fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other
 *   liability, whether in an action of contract, tort or otherwise, arising from,
 *   out of or in connection with the Software or the use or other dealings in the
 *   Software.
 */
package eth.serotine.ui.components

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A QR Code (Model 2) of some bytes, in byte mode: the grid of dark and light modules a
 * [QrCode] draws. It reproduces the reference library's `encode_binary` module for module —
 * the smallest version that fits, the error correction raised as far as that version allows,
 * the mask with the lowest penalty — which QrMatrixTest pins with vectors from it.
 */
class QrMatrix private constructor(
    /** 1 to 40. */
    val version: Int,
    /** The level used, which may be above the one asked for. */
    val ecc: Ecc,
    dataCodewords: IntArray,
) {
    /** Modules per side: 21 to 177. */
    val size: Int = version * 4 + 17

    /** 0 to 7, chosen for the lowest penalty. */
    val mask: Int

    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    init {
        drawFunctionPatterns()
        drawCodewords(addEccAndInterleave(dataCodewords))
        var chosen = 0
        var minPenalty = Int.MAX_VALUE
        for (candidate in 0 until 8) {
            applyMask(candidate)
            drawFormatBits(candidate)
            val penalty = penaltyScore()
            if (penalty < minPenalty) {
                chosen = candidate
                minPenalty = penalty
            }
            applyMask(candidate) // XOR undoes it
        }
        mask = chosen
        applyMask(mask)
        drawFormatBits(mask)
    }

    /** Whether the module at column [x], row [y] is dark; outside the grid, light. */
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    /** The error correction levels, with the share of codewords each can restore. */
    enum class Ecc(internal val formatBits: Int) {
        /** About 7 %. */
        LOW(1),

        /** About 15 %. */
        MEDIUM(0),

        /** About 25 %. */
        QUARTILE(3),

        /** About 30 %. */
        HIGH(2),
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)
        val positions = alignmentPatternPositions()
        val last = positions.size - 1
        for (i in positions.indices) {
            for (j in positions.indices) {
                // Not on the three finder corners.
                if (!(i == 0 && j == 0) && !(i == 0 && j == last) && !(i == last && j == 0)) {
                    drawAlignmentPattern(positions[i], positions[j])
                }
            }
        }
        drawFormatBits(0) // a placeholder, overwritten once the mask is chosen
        drawVersion()
    }

    private fun drawFormatBits(mask: Int) {
        val data = ecc.formatBits shl 3 or mask
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
        val bits = (data shl 10 or remainder) xor 0x5412
        for (i in 0..5) setFunction(8, i, bit(bits, i))
        setFunction(8, 7, bit(bits, 6))
        setFunction(8, 8, bit(bits, 7))
        setFunction(7, 8, bit(bits, 8))
        for (i in 9 until 15) setFunction(14 - i, 8, bit(bits, i))
        for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(bits, i))
        for (i in 8 until 15) setFunction(8, size - 15 + i, bit(bits, i))
        setFunction(8, size - 8, true) // always dark
    }

    private fun drawVersion() {
        if (version < 7) return
        var remainder = version
        repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
        val bits = version shl 12 or remainder
        for (i in 0 until 18) {
            val dark = bit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, dark)
            setFunction(b, a, dark)
        }
    }

    private fun drawFinderPattern(x: Int, y: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) {
                    val distance = max(abs(dx), abs(dy))
                    setFunction(xx, yy, distance != 2 && distance != 4)
                }
            }
        }
    }

    private fun drawAlignmentPattern(x: Int, y: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) setFunction(x + dx, y + dy, max(abs(dx), abs(dy)) != 1)
        }
    }

    private fun setFunction(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        isFunction[y][x] = true
    }

    private fun addEccAndInterleave(data: IntArray): IntArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]
        val blockEccLength = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLength = rawCodewords / numBlocks
        val divisor = reedSolomonDivisor(blockEccLength)
        var k = 0
        val blocks = List(numBlocks) { i ->
            val length = shortBlockLength - blockEccLength + if (i < numShortBlocks) 0 else 1
            val block = data.copyOfRange(k, k + length)
            k += length
            val padding = if (i < numShortBlocks) intArrayOf(0) else IntArray(0)
            block + padding + reedSolomonRemainder(block, divisor)
        }
        val result = IntArray(rawCodewords)
        var n = 0
        for (i in blocks[0].indices) {
            for (j in blocks.indices) {
                // The padding of the short blocks is skipped.
                if (i != shortBlockLength - blockEccLength || j >= numShortBlocks) result[n++] = blocks[j][i]
            }
        }
        return result
    }

    private fun drawCodewords(data: IntArray) {
        var i = 0
        var column = size - 1
        while (column >= 1) {
            // The vertical timing pattern's column is skipped.
            val right = if (column <= 6) column - 1 else column
            for (vertical in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vertical else vertical
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = bit(data[i ushr 3], 7 - (i and 7))
                        i++
                    }
                }
            }
            column -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2
                    1 -> y % 2
                    2 -> x % 3
                    3 -> (x + y) % 3
                    4 -> (x / 3 + y / 2) % 2
                    5 -> x * y % 2 + x * y % 3
                    6 -> (x * y % 2 + x * y % 3) % 2
                    else -> ((x + y) % 2 + x * y % 3) % 2
                } == 0
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }
    }

    private fun penaltyScore(): Int {
        var result = 0
        for (y in 0 until size) result += linePenalty { modules[y][it] }
        for (x in 0 until size) result += linePenalty { modules[it][x] }
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val color = modules[y][x]
                if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                    result += PENALTY_N2
                }
            }
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
        return result + k * PENALTY_N4
    }

    /** Runs of one colour, and finder-like patterns, along one row or column. */
    private inline fun linePenalty(module: (Int) -> Boolean): Int {
        var result = 0
        var runColor = false
        var run = 0
        val history = IntArray(7)
        for (i in 0 until size) {
            if (module(i) == runColor) {
                run++
                if (run == 5) result += PENALTY_N1 else if (run > 5) result++
            } else {
                addHistory(run, history)
                if (!runColor) result += countFinderPatterns(history) * PENALTY_N3
                runColor = module(i)
                run = 1
            }
        }
        if (runColor) {
            addHistory(run, history)
            run = 0
        }
        addHistory(run + size, history) // the light border after the line
        return result + countFinderPatterns(history) * PENALTY_N3
    }

    /** Pushes [run] onto [history], newest first; the line's first run gets the light border before it. */
    private fun addHistory(run: Int, history: IntArray) {
        val length = if (history[0] == 0) run + size else run
        history.copyInto(history, destinationOffset = 1, startIndex = 0, endIndex = history.size - 1)
        history[0] = length
    }

    /** 0, 1 or 2: the 1:1:3:1:1 finder-like patterns that end at the light run just added. */
    private fun countFinderPatterns(history: IntArray): Int {
        val n = history[1]
        val core = n > 0 && history[2] == n && history[4] == n && history[5] == n && history[3] == n * 3
        return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
            (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
    }

    private fun alignmentPatternPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val positions = IntArray(count)
        positions[0] = 6
        for (i in 0 until count - 1) positions[count - 1 - i] = size - 7 - i * step
        return positions
    }

    companion object {
        /**
         * [data] as a QR Code at [ecc] or above; throws when it is more than a version-40 code
         * holds (2953 bytes at LOW).
         */
        fun encode(data: ByteArray, ecc: Ecc = Ecc.MEDIUM): QrMatrix {
            var version = 1
            var usedBits: Int
            while (true) {
                val countBits = if (version <= 9) 8 else 16
                val capacityBits = numDataCodewords(version, ecc) * 8
                usedBits = 4 + countBits + data.size * 8
                if (data.size < (1 shl countBits) && usedBits <= capacityBits) break
                require(version < MAX_VERSION) { "${data.size} bytes do not fit a QR Code" }
                version++
            }
            var level = ecc
            for (higher in listOf(Ecc.MEDIUM, Ecc.QUARTILE, Ecc.HIGH)) {
                if (usedBits <= numDataCodewords(version, higher) * 8) level = higher
            }
            val capacityBits = numDataCodewords(version, level) * 8
            val bits = BitBuffer()
            bits.append(0x4, 4) // byte mode
            bits.append(data.size, if (version <= 9) 8 else 16)
            data.forEach { bits.append(it.toInt() and 0xff, 8) }
            bits.append(0, min(4, capacityBits - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) {
                bits.append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            return QrMatrix(version, level, bits.toCodewords())
        }

        private const val MAX_VERSION = 40
        private const val PENALTY_N1 = 3
        private const val PENALTY_N2 = 3
        private const val PENALTY_N3 = 40
        private const val PENALTY_N4 = 10

        private fun bit(value: Int, index: Int): Boolean = (value ushr index) and 1 != 0

        private fun numRawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val count = version / 7 + 2
                result -= (25 * count - 10) * count - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun numDataCodewords(version: Int, ecc: Ecc): Int =
            numRawDataModules(version) / 8 -
                ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version] * NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]

        private fun reedSolomonDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1 // the monomial x^0
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = reedSolomonMultiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = reedSolomonMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                result.copyInto(result, destinationOffset = 0, startIndex = 1)
                result[result.size - 1] = 0
                for (i in divisor.indices) result[i] = result[i] xor reedSolomonMultiply(divisor[i], factor)
            }
            return result
        }

        /** The product in GF(2^8/0x11D). */
        private fun reedSolomonMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        // Indexed by Ecc.ordinal (LOW, MEDIUM, QUARTILE, HIGH), then version; index 0 is unused.
        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            intArrayOf(
                -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
                28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
            intArrayOf(
                -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
                26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
            ),
            intArrayOf(
                -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30,
                28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
            intArrayOf(
                -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28,
                30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            ),
        )

        private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
            intArrayOf(
                -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8,
                8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25,
            ),
            intArrayOf(
                -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
                17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
            ),
            intArrayOf(
                -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20,
                23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68,
            ),
            intArrayOf(
                -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25,
                25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81,
            ),
        )
    }
}

/** Bits appended most significant first, packed into codewords at the end. */
private class BitBuffer {
    private val bits = ArrayList<Boolean>()
    val size: Int get() = bits.size

    fun append(value: Int, length: Int) {
        for (i in length - 1 downTo 0) bits += (value ushr i) and 1 != 0
    }

    fun toCodewords(): IntArray = IntArray(bits.size / 8) { i ->
        (0 until 8).fold(0) { byte, j -> byte shl 1 or if (bits[i * 8 + j]) 1 else 0 }
    }
}
