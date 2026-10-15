package eth.serotine.data.keys

import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File

/**
 * libsodium's `crypto_pwhash`, mapped with byte arrays. The bindings' own API takes the
 * password as a String and passes its UTF-16 length as the byte count, while JNA encodes
 * the String to UTF-8: a non-ASCII password would be hashed only up to that many bytes,
 * and its bytes could not be zeroed. So the envelope maps the two functions it needs
 * itself, over the same libsodium binaries the bindings ship: on Android the APK's
 * `libsodium.so`, on the desktop the library for this OS and architecture, copied out of
 * the bindings' jar once per process.
 */
internal object Sodium {

    @Suppress("FunctionName") // libsodium's own names
    interface Library : com.sun.jna.Library {
        fun sodium_init(): Int

        fun crypto_pwhash_alg_argon2id13(): Int

        fun crypto_pwhash(
            out: ByteArray,
            outlen: Long, // unsigned long long
            passwd: ByteArray,
            passwdlen: Long, // unsigned long long
            salt: ByteArray,
            opslimit: Long, // unsigned long long
            memlimit: SizeT,
            alg: Int,
        ): Int
    }

    /** `size_t`, whatever its width on this platform (32 bits on 32-bit Android ABIs, 64 on Windows x64). */
    class SizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
        override fun toByte(): Byte = toLong().toByte()
        override fun toShort(): Short = toLong().toShort()

        @Deprecated("Number.toChar() is deprecated", ReplaceWith("toInt().toChar()"))
        override fun toChar(): Char = toInt().toChar()
    }

    /** The library, or null when it cannot be loaded here; loaded once. */
    val library: Library? by lazy {
        try {
            val loaded = Native.load(libraryName(), Library::class.java)
            // 0: initialized now, 1: already initialized; -1: failed.
            if (loaded.sodium_init() < 0) null else loaded
        } catch (e: Throwable) {
            println("Serotine: libsodium cannot be loaded (${e::class.simpleName})")
            null
        }
    }

    private fun libraryName(): String {
        if (isAndroid()) return "sodium"
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val resource = when {
            "mac" in os -> "libdynamic-macos.dylib"
            "win" in os -> "libdynamic-msvc-x86-64-libsodium.dll"
            arch == "aarch64" || arch == "arm64" -> "libdynamic-linux-arm64-libsodium.so"
            else -> "libdynamic-linux-x86-64-libsodium.so"
        }
        val stream = Sodium::class.java.classLoader.getResourceAsStream(resource)
            ?: error("no libsodium for $os/$arch")
        val file = File.createTempFile("serotine-libsodium", resource.substringAfterLast('.').let { ".$it" })
        file.deleteOnExit()
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    private fun isAndroid(): Boolean =
        System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)
}
