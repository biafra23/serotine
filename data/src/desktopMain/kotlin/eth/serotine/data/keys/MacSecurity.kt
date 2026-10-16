package eth.serotine.data.keys

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * The few CoreFoundation and Security.framework calls [MacSecureEnclaveSecret] needs, mapped
 * with JNA: no native build step, the frameworks are on every Mac. A failing call throws an
 * [IllegalStateException] naming the call and its error code, never key material.
 *
 * The enclave key's handle is read and restored through its token object id, the attribute
 * `"toid"`. The SDK does not name it, but it is what CryptoKit's own
 * `SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation:)` stands on. If a macOS
 * release changed it, [MacSecureEnclaveSecret.usable] would say no, so a new wallet would
 * stay password-only, and a wallet already on the enclave would find its key store
 * unavailable until the handle could be read again.
 */
internal class MacSecurity private constructor() {

    @Suppress("FunctionName") // CoreFoundation's own names
    private interface CoreFoundation : Library {
        fun CFStringCreateWithCString(allocator: Pointer?, string: String, encoding: Int): Pointer?
        fun CFNumberCreate(allocator: Pointer?, type: Long, value: Pointer): Pointer?
        fun CFDictionaryCreate(
            allocator: Pointer?,
            keys: Array<Pointer>,
            values: Array<Pointer>,
            count: Long,
            keyCallBacks: Pointer,
            valueCallBacks: Pointer,
        ): Pointer?
        fun CFDictionaryGetValue(dictionary: Pointer, key: Pointer): Pointer?
        fun CFDataCreate(allocator: Pointer?, bytes: Pointer, length: Long): Pointer?
        fun CFDataGetLength(data: Pointer): Long
        fun CFDataGetBytePtr(data: Pointer): Pointer?
        fun CFErrorGetCode(error: Pointer): Long
        fun CFGetTypeID(cf: Pointer): Long
        fun CFDataGetTypeID(): Long
        fun CFRelease(cf: Pointer)
    }

    @Suppress("FunctionName") // Security.framework's own names
    private interface Security : Library {
        fun SecKeyCreateRandomKey(parameters: Pointer, error: PointerByReference): Pointer?
        fun SecKeyCopyAttributes(key: Pointer): Pointer?
        fun SecKeyCreateWithData(keyData: Pointer, attributes: Pointer, error: PointerByReference): Pointer?
        fun SecKeyCopyPublicKey(key: Pointer): Pointer?
        fun SecKeyCreateEncryptedData(
            key: Pointer,
            algorithm: Pointer,
            plaintext: Pointer,
            error: PointerByReference,
        ): Pointer?
        fun SecKeyCreateDecryptedData(
            key: Pointer,
            algorithm: Pointer,
            ciphertext: Pointer,
            error: PointerByReference,
        ): Pointer?
    }

    private val cf = Native.load(CORE_FOUNDATION, CoreFoundation::class.java)
    private val sec = Native.load(SECURITY, Security::class.java)
    private val cfLibrary = NativeLibrary.getInstance(CORE_FOUNDATION)
    private val secLibrary = NativeLibrary.getInstance(SECURITY)

    private fun cfConstant(name: String): Pointer = cfLibrary.getGlobalVariableAddress(name).getPointer(0)
    private fun secConstant(name: String): Pointer = secLibrary.getGlobalVariableAddress(name).getPointer(0)

    private val keyCallBacks = cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
    private val valueCallBacks = cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")
    private val falseValue = cfConstant("kCFBooleanFalse")
    private val keyType = secConstant("kSecAttrKeyType")
    private val ecKeyType = secConstant("kSecAttrKeyTypeECSECPrimeRandom")
    private val keySizeInBits = secConstant("kSecAttrKeySizeInBits")
    private val tokenId = secConstant("kSecAttrTokenID")
    private val secureEnclave = secConstant("kSecAttrTokenIDSecureEnclave")
    private val privateKeyAttributes = secConstant("kSecPrivateKeyAttrs")
    private val isPermanent = secConstant("kSecAttrIsPermanent")
    private val keyClass = secConstant("kSecAttrKeyClass")
    private val privateKeyClass = secConstant("kSecAttrKeyClassPrivate")
    private val ecies = secConstant("kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM")
    private val tokenObjectId = checkNotNull(cf.CFStringCreateWithCString(null, "toid", UTF8)) { "CFString" }

    /**
     * A new P-256 key inside the Secure Enclave, kept nowhere but in its own handle ([tokenObjectIdOf]):
     * not permanent, so no keychain item and no keychain entitlement, and with no user-authentication
     * requirement (the envelope's `HW_pw`). The caller releases it.
     */
    fun createEnclaveKey(): Pointer = owning { own ->
        val bits = own(number(256))
        val privateAttributes = own(dictionary(isPermanent to falseValue))
        val parameters = own(
            dictionary(
                keyType to ecKeyType,
                keySizeInBits to bits,
                tokenId to secureEnclave,
                privateKeyAttributes to privateAttributes,
            ),
        )
        call("SecKeyCreateRandomKey") { error -> sec.SecKeyCreateRandomKey(parameters, error) }
    }

    /** The enclave's encrypted handle to [key]: useless anywhere but in this Mac's Secure Enclave. */
    fun tokenObjectIdOf(key: Pointer): ByteArray {
        val attributes = checkNotNull(sec.SecKeyCopyAttributes(key)) { "SecKeyCopyAttributes failed" }
        try {
            val handle = checkNotNull(cf.CFDictionaryGetValue(attributes, tokenObjectId)) {
                "the key has no token object id"
            }
            check(cf.CFGetTypeID(handle) == cf.CFDataGetTypeID()) { "the key's token object id is not data" }
            return bytesOf(handle) // owned by the attributes dictionary, so copied before it goes
        } finally {
            release(attributes)
        }
    }

    /** The enclave key [handle] ([tokenObjectIdOf]) refers to. The caller releases it. */
    fun restoreEnclaveKey(handle: ByteArray): Pointer = owning { own ->
        val data = own(data(handle))
        val attributes = own(
            dictionary(
                keyType to ecKeyType,
                keyClass to privateKeyClass,
                tokenId to secureEnclave,
                tokenObjectId to data,
            ),
        )
        call("SecKeyCreateWithData") { error -> sec.SecKeyCreateWithData(data, attributes, error) }
    }

    /**
     * [plaintext] encrypted to [privateKey]'s public half with ECIES (X9.63 KDF, AES-GCM); the
     * plaintext copies are zeroed.
     */
    fun eciesEncrypt(privateKey: Pointer, plaintext: ByteArray): ByteArray = owning { own ->
        val publicKey = own(checkNotNull(sec.SecKeyCopyPublicKey(privateKey)) { "SecKeyCopyPublicKey failed" })
        val input = own(data(plaintext))
        try {
            val output = own(
                call("SecKeyCreateEncryptedData") { error ->
                    sec.SecKeyCreateEncryptedData(publicKey, ecies, input, error)
                },
            )
            bytesOf(output)
        } finally {
            zero(input)
        }
    }

    /**
     * [ciphertext] decrypted by the enclave with [privateKey]. The caller zeroes the result;
     * CF's copy is zeroed here.
     */
    fun eciesDecrypt(privateKey: Pointer, ciphertext: ByteArray): ByteArray {
        val input = data(ciphertext)
        try {
            val output = call("SecKeyCreateDecryptedData") { error ->
                sec.SecKeyCreateDecryptedData(privateKey, ecies, input, error)
            }
            try {
                return bytesOf(output)
            } finally {
                zero(output)
                release(output)
            }
        } finally {
            release(input)
        }
    }

    fun release(vararg objects: Pointer) = objects.forEach(cf::CFRelease)

    /**
     * Runs [block], releasing every CF object it hands to `own` when it ends, however it ends:
     * no failure path leaks one. What [block] returns is not released.
     */
    private fun <T> owning(block: (own: (Pointer) -> Pointer) -> T): T {
        val owned = mutableListOf<Pointer>()
        try {
            return block { cfObject -> cfObject.also(owned::add) }
        } finally {
            owned.asReversed().forEach(cf::CFRelease)
        }
    }

    private fun call(name: String, block: (PointerByReference) -> Pointer?): Pointer {
        val error = PointerByReference()
        block(error)?.let { return it }
        val code = error.value?.let { cfError -> cf.CFErrorGetCode(cfError).also { cf.CFRelease(cfError) } }
        error("$name failed (${code ?: "no error"})")
    }

    private fun dictionary(vararg entries: Pair<Pointer, Pointer>): Pointer = checkNotNull(
        cf.CFDictionaryCreate(
            null,
            Array(entries.size) { entries[it].first },
            Array(entries.size) { entries[it].second },
            entries.size.toLong(),
            keyCallBacks,
            valueCallBacks,
        ),
    ) { "CFDictionaryCreate failed" }

    private fun number(value: Int): Pointer {
        val memory = Memory(4).apply { setInt(0, value) }
        return checkNotNull(cf.CFNumberCreate(null, SINT32_TYPE, memory)) { "CFNumberCreate failed" }
    }

    /** A CFData copy of [bytes], made through a native buffer that is zeroed afterwards. */
    private fun data(bytes: ByteArray): Pointer {
        val memory = Memory(maxOf(1, bytes.size).toLong())
        try {
            memory.write(0, bytes, 0, bytes.size)
            return checkNotNull(cf.CFDataCreate(null, memory, bytes.size.toLong())) { "CFDataCreate failed" }
        } finally {
            memory.clear()
            memory.close()
        }
    }

    private fun bytesOf(data: Pointer): ByteArray {
        val length = cf.CFDataGetLength(data).toInt()
        return cf.CFDataGetBytePtr(data)?.getByteArray(0, length) ?: ByteArray(0)
    }

    /**
     * Overwrites [data]'s bytes before it is released: CF frees its buffers without zeroing
     * them. The CFData is immutable by contract; writing through its byte pointer is what
     * this file does anyway, as the only way to reach that copy.
     */
    private fun zero(data: Pointer) {
        cf.CFDataGetBytePtr(data)?.clear(cf.CFDataGetLength(data))
    }

    companion object {
        private const val CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        private const val SECURITY = "/System/Library/Frameworks/Security.framework/Security"
        private const val UTF8 = 0x08000100 // kCFStringEncodingUTF8
        private const val SINT32_TYPE = 3L // kCFNumberSInt32Type

        val onMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        /** The frameworks, or null off a Mac or when they cannot be loaded; loaded once. */
        val instance: MacSecurity? by lazy {
            if (!onMac) return@lazy null
            try {
                MacSecurity()
            } catch (e: Throwable) {
                println("Serotine: Security.framework cannot be loaded (${e::class.simpleName})")
                null
            }
        }
    }
}
