package eth.serotine.data.keys

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA512

/**
 * BIP-39 over the English wordlist: checking words, and stretching them into a seed.
 * Every secret intermediate this object creates is a byte array zeroed before it is
 * dropped (docs/keys-and-signing.md, rule 7); the seed it returns is the caller's to zero.
 * The words themselves arrive as Strings, which nothing can zero.
 */
internal object Bip39 {

    private const val BITS_PER_WORD = 11
    private const val PBKDF2_ROUNDS = 2048

    /**
     * The words of [entropy] (16 to 32 bytes, a multiple of 4): its bits followed by the
     * first (bits / 32) bits of its SHA-256, read 11 bits per word from the English list.
     * The caller zeroes [entropy].
     */
    fun wordsOf(entropy: ByteArray): List<String> {
        require(entropy.size in 16..32 && entropy.size % 4 == 0) { "BIP-39 entropy is 128 to 256 bits in steps of 32" }
        val hash = SHA256().digest(entropy)
        try {
            val entropyBits = entropy.size * 8
            val totalBits = entropyBits + entropyBits / 32
            fun bit(i: Int): Int = if (i < entropyBits) {
                (entropy[i / 8].toInt() shr (7 - i % 8)) and 1
            } else {
                (hash[(i - entropyBits) / 8].toInt() shr (7 - (i - entropyBits) % 8)) and 1
            }
            return (0 until totalBits / BITS_PER_WORD).map { word ->
                var value = 0
                for (b in 0 until BITS_PER_WORD) value = (value shl 1) or bit(word * BITS_PER_WORD + b)
                BIP39_ENGLISH[value]
            }
        } finally {
            hash.fill(0)
        }
    }

    /** Why [words] are not a BIP-39 mnemonic, or null when they are one. */
    fun problemWith(words: List<String>): MnemonicProblem? {
        if (words.size !in Mnemonic.WORD_COUNTS) return MnemonicProblem.WordCount(words.size)
        val values = IntArray(words.size)
        for ((i, word) in words.withIndex()) {
            val value = BIP39_ENGLISH.binarySearch(word)
            if (value < 0) {
                values.fill(0)
                return MnemonicProblem.UnknownWord(position = i + 1)
            }
            values[i] = value
        }
        // The words' bits are the entropy followed by its checksum: the first
        // (entropy bits / 32) bits of SHA-256(entropy).
        val checksumBits = words.size * BITS_PER_WORD / 33
        val entropyBits = words.size * BITS_PER_WORD - checksumBits
        val entropy = ByteArray(entropyBits / 8)
        var hash = ByteArray(0)
        try {
            for (bit in 0 until entropyBits) {
                if (bitOf(values, bit)) entropy[bit / 8] = (entropy[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
            }
            hash = SHA256().digest(entropy)
            val matches = (0 until checksumBits).all { bit ->
                bitOf(values, entropyBits + bit) == ((hash[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1)
            }
            return if (matches) null else MnemonicProblem.Checksum
        } finally {
            values.fill(0)
            entropy.fill(0)
            hash.fill(0)
        }
    }

    /**
     * The 64-byte seed of [words], which must have passed [problemWith]: PBKDF2 with
     * HMAC-SHA512 over the words joined by single spaces, salted with "mnemonic" +
     * [passphrase], 2048 rounds.
     *
     * BIP-39 NFKD-normalizes the sentence and the passphrase. The English words are ASCII,
     * where NFKD changes nothing; so is [passphrase] or it is refused, rather than
     * stretched without the normalization into a seed no other wallet would derive.
     */
    fun seed(words: List<String>, passphrase: String = ""): ByteArray {
        require(passphrase.all { it.code < 0x80 }) { "only an ASCII passphrase is supported" }
        val password = sentence(words)
        val mac = HmacSHA512(password)
        var u = ByteArray(0)
        try {
            // One PBKDF2 block: the seed is exactly as long as an HMAC-SHA512 output.
            mac.update(("mnemonic$passphrase").encodeToByteArray())
            mac.update(byteArrayOf(0, 0, 0, 1))
            u = mac.doFinal()
            val seed = u.copyOf()
            repeat(PBKDF2_ROUNDS - 1) {
                val next = mac.doFinal(u)
                u.fill(0)
                u = next
                for (i in seed.indices) seed[i] = (seed[i].toInt() xor u[i].toInt()).toByte()
            }
            return seed
        } finally {
            u.fill(0)
            password.fill(0)
            mac.clearKey()
        }
    }

    /**
     * The mnemonic sentence, the words joined by single spaces, as UTF-8 written straight
     * into one byte array: no intermediate String of the whole phrase. The words passed
     * [problemWith], so each is ASCII and a char is its byte.
     */
    private fun sentence(words: List<String>): ByteArray {
        val bytes = ByteArray(words.sumOf { it.length } + words.size - 1)
        var at = 0
        for ((i, word) in words.withIndex()) {
            if (i > 0) bytes[at++] = ' '.code.toByte()
            for (c in word) bytes[at++] = c.code.toByte()
        }
        return bytes
    }

    /** Bit [bit] of the words' bit string, most significant first. */
    private fun bitOf(values: IntArray, bit: Int): Boolean =
        (values[bit / BITS_PER_WORD] shr (BITS_PER_WORD - 1 - bit % BITS_PER_WORD)) and 1 == 1
}
