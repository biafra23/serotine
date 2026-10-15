package eth.serotine.data.keys

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/** HKDF with HMAC-SHA256 (RFC 5869): extract, then expand to [length] bytes. Intermediates are zeroed. */
internal object Hkdf {

    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF-SHA256 yields at most 8160 bytes" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        try {
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var written = 0
            var counter = 1
            while (written < length) {
                val block = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                previous.fill(0)
                previous = block
                val take = minOf(block.size, length - written)
                block.copyInto(out, written, 0, take)
                written += take
                counter++
            }
            previous.fill(0)
            return out
        } finally {
            prk.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HmacSHA256(key)
        try {
            return mac.doFinal(data)
        } finally {
            mac.clearKey()
        }
    }
}
