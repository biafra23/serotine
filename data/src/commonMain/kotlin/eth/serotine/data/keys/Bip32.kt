package eth.serotine.data.keys

import fr.acinq.secp256k1.Secp256k1
import org.kotlincrypto.macs.hmac.sha2.HmacSHA512

/**
 * BIP-32 private-key derivation, with libsecp256k1 doing the curve arithmetic. Every key
 * and chain code is a byte array the code zeroes once it is spent (docs/keys-and-signing.md,
 * rule 7); what a function returns is the caller's to zero. The bindings' own transient
 * copies (the JNI side's stack buffers, cinterop's scratch memory) are beyond its reach.
 */
internal object Bip32 {

    /** The bit that marks a hardened child index (BIP-32's `i ≥ 2^31`), as an [Int]. */
    const val HARDENED: Int = Int.MIN_VALUE

    private val MASTER_HMAC_KEY = "Bitcoin seed".encodeToByteArray()

    /** A private key and its chain code, 32 bytes each. [toString] is redacted. */
    class ExtendedKey(val key: ByteArray, val chainCode: ByteArray) {
        /** Zeroes both halves. */
        fun wipe() {
            key.fill(0)
            chainCode.fill(0)
        }

        override fun toString(): String = "ExtendedKey(redacted)"
    }

    /** The master key of [seed]. */
    fun master(seed: ByteArray): ExtendedKey {
        val master = split(hmacSha512(MASTER_HMAC_KEY, seed))
        // A zero or out-of-range master key: BIP-32 calls the seed invalid. 1 in 2^127.
        check(Secp256k1.secKeyVerify(master.key)) { master.wipe(); "the seed has no valid BIP-32 master key" }
        return master
    }

    /** Child [index] of [parent] (CKDpriv); hardened when [index] has [HARDENED] set. */
    fun child(parent: ExtendedKey, index: Int): ExtendedKey {
        val data = ByteArray(37)
        if (index and HARDENED != 0) {
            parent.key.copyInto(data, destinationOffset = 1) // 0x00 || k || index
        } else {
            val publicKey = Secp256k1.pubkeyCreate(parent.key)
            Secp256k1.pubKeyCompress(publicKey).copyInto(data) // serP(K) || index
        }
        for (i in 0 until 4) data[33 + i] = (index ushr (24 - 8 * i)).toByte()
        val derived = split(hmacSha512(parent.chainCode, data))
        data.fill(0)
        // The child key is IL + k (mod n), returned as a new array by both bindings, which
        // leave their arguments untouched. libsecp256k1 refuses an IL >= n or a zero sum: the
        // 1-in-2^127 case BIP-32 answers by skipping to the next index, which on a fixed path
        // would quietly name another account — so it stays the exception it throws.
        try {
            return ExtendedKey(Secp256k1.privKeyTweakAdd(parent.key, derived.key), derived.chainCode)
        } catch (e: Throwable) {
            derived.chainCode.fill(0)
            throw e
        } finally {
            derived.key.fill(0)
        }
    }

    /** The private key at [path] below the master key of [seed]. */
    fun derive(seed: ByteArray, path: IntArray): ByteArray {
        var current = master(seed)
        try {
            for (index in path) {
                val next = child(current, index)
                current.wipe()
                current = next
            }
        } catch (e: Throwable) {
            current.wipe()
            throw e
        }
        current.chainCode.fill(0)
        return current.key
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HmacSHA512(key)
        try {
            return mac.doFinal(data)
        } finally {
            mac.clearKey()
        }
    }

    /** IL and IR of a 64-byte HMAC output, which is zeroed. */
    private fun split(i: ByteArray): ExtendedKey {
        val key = i.copyOfRange(0, 32)
        val chainCode = i.copyOfRange(32, 64)
        i.fill(0)
        return ExtendedKey(key, chainCode)
    }
}
