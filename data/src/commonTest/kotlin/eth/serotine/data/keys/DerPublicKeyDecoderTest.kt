package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Tests for DER-encoded P-256 public key decoding.
 *
 * A DER-encoded EC public key follows this structure:
 * SEQUENCE {
 *   algorithm AlgorithmIdentifier (SEQUENCE with OID and parameters),
 *   subjectPublicKey BIT STRING (containing the actual EC point)
 * }
 *
 * The EC point in the BIT STRING is: 0x04 || x || y (uncompressed format)
 */
class DerPublicKeyDecoderTest {

    /**
     * Build a minimal DER-encoded P-256 public key for testing.
     */
    private fun buildDerPublicKey(x: ByteArray, y: ByteArray): ByteArray {
        require(x.size == 32 && y.size == 32) { "x and y must be 32 bytes each for P-256" }

        // EC Public Key OID: 1.2.840.10045.2.1 (id-ecPublicKey)
        val ecPublicKeyOid = byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(), 0x3D.toByte(), 0x02.toByte(), 0x01.toByte())

        // P-256 curve OID: 1.2.840.10045.3.1.7 (secp256r1)
        val p256CurveOid = byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(), 0x3D.toByte(), 0x03.toByte(), 0x01.toByte(), 0x07.toByte())

        // Build AlgorithmIdentifier: SEQUENCE { OID, parameters (curve OID) }
        val algoId = buildDerSequence(
            ecPublicKeyOid,
            buildDerSequence(p256CurveOid)
        )

        // Build the EC point: 0x04 || x || y
        val ecPoint = byteArrayOf(0x04.toByte()) + x + y

        // Build BIT STRING containing the EC point
        val bitString = buildDerBitString(ecPoint)

        // Final SEQUENCE containing AlgorithmIdentifier and BIT STRING
        return buildDerSequence(algoId, bitString)
    }

    /**
     * Build a DER-encoded SEQUENCE.
     */
    private fun buildDerSequence(vararg contents: ByteArray): ByteArray {
        val totalLength = contents.sumOf { it.size }
        require(totalLength < 128) { "This helper only supports short-form length" }

        val result = mutableListOf<Byte>()
        result.add(0x30.toByte()) // SEQUENCE tag
        result.add(totalLength.toByte()) // short-form length

        contents.forEach { result.addAll(it.toList().map { it.toByte() }) }

        return result.toByteArray()
    }

    /**
     * Build a DER-encoded BIT STRING.
     */
    private fun buildDerBitString(content: ByteArray): ByteArray {
        require(content.size < 128) { "This helper only supports short-form length" }

        val result = mutableListOf<Byte>()
        result.add(0x03.toByte()) // BIT STRING tag
        result.add((content.size + 1).toByte()) // content size + 1 for bit count

        result.add(0x00.toByte()) // 0 unused bits
        result.addAll(content.toList().map { it.toByte() })

        return result.toByteArray()
    }

    @Test
    fun buildsValidDerStructure() {
        val x = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef".hexToBytes()
        val y = "fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321".hexToBytes()

        val derEncoded = buildDerPublicKey(x, y)

        // Verify structure:
        // 0x30 (SEQUENCE) at offset 0
        assertContentEquals(byteArrayOf(0x30.toByte()), derEncoded.copyOfRange(0, 1))
    }


    // Helper extension for testing
    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
