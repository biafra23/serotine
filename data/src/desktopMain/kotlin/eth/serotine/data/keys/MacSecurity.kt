package eth.serotine.data.keys

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * The few CoreFoundation and Security.framework calls [MacSecureEnclaveSecret] and
 * [MacBiometricKey] need, mapped
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
        fun CFErrorGetDomain(error: Pointer): Pointer?
        fun CFErrorCopyUserInfo(error: Pointer): Pointer?

        // Boolean is one byte: JNA's Kotlin Boolean would read the register's upper bits too.
        fun CFStringGetCString(string: Pointer, buffer: Memory, size: Long, encoding: Int): Byte
        fun CFGetTypeID(cf: Pointer): Long
        fun CFDataGetTypeID(): Long
        fun CFErrorGetTypeID(): Long
        fun CFRelease(cf: Pointer)
    }

    @Suppress("FunctionName") // Security.framework's own names
    private interface Security : Library {
        fun SecAccessControlCreateWithFlags(
            allocator: Pointer?,
            protection: Pointer,
            flags: Long,
            error: PointerByReference,
        ): Pointer?
        fun SecKeyCreateRandomKey(parameters: Pointer, error: PointerByReference): Pointer?
        fun SecKeyCopyAttributes(key: Pointer): Pointer?
        fun SecKeyCopyExternalRepresentation(key: Pointer, error: PointerByReference): Pointer?
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

    private val underlyingError = cfConstant("kCFErrorUnderlyingErrorKey")
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
    private val accessControl = secConstant("kSecAttrAccessControl")
    private val whenPasscodeSetThisDeviceOnly = secConstant("kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly")
    private val authenticationContext = secConstant("kSecUseAuthenticationContext")
    private val keyClass = secConstant("kSecAttrKeyClass")
    private val privateKeyClass = secConstant("kSecAttrKeyClassPrivate")
    private val ecies = secConstant("kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM")
    private val tokenObjectId = checkNotNull(cf.CFStringCreateWithCString(null, "toid", UTF8)) { "CFString" }

    /**
     * A new P-256 key inside the Secure Enclave, kept nowhere but in its own handle ([tokenObjectIdOf]):
     * not permanent, so no keychain item and no keychain entitlement, and with no user-authentication
     * requirement (the envelope's `HW_pw`). The caller releases it.
     */
    fun createEnclaveKey(): Pointer = createEnclaveKey(access = null)

    /**
     * A new P-256 key inside the Secure Enclave for the envelope's biometric path (`HW_bio`, rule 3
     * of docs/keys-and-signing.md), made as [createEnclaveKey]'s but under
     * `[.privateKeyUsage, .biometryCurrentSet]` and `WhenPasscodeSetThisDeviceOnly`: every use of
     * its private half needs Touch ID of the fingers enrolled now, the enclave enforcing it, and
     * removing the login password or changing the fingers makes the key useless. No password
     * fallback (`.userPresence` would be one). The caller releases it.
     */
    fun createBiometricEnclaveKey(): Pointer = owning { own ->
        val access = own(
            call("SecAccessControlCreateWithFlags") { error ->
                sec.SecAccessControlCreateWithFlags(
                    null,
                    whenPasscodeSetThisDeviceOnly,
                    PRIVATE_KEY_USAGE or BIOMETRY_CURRENT_SET,
                    error,
                )
            },
        )
        createEnclaveKey(access)
    }

    private fun createEnclaveKey(access: Pointer?): Pointer = owning { own ->
        val bits = own(number(256))
        val privateAttributes = own(
            if (access == null) {
                dictionary(isPermanent to falseValue)
            } else {
                dictionary(isPermanent to falseValue, accessControl to access)
            },
        )
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

    /**
     * A new P-256 key inside the Secure Enclave for [newHardwareP256Key]:
     * returns the public key's x and y coordinates as 32-byte arrays each.
     * The key itself is not stored anywhere; only its coordinates are returned.
     *
     * On macOS, we create a temporary key in the Secure Enclave with
     * kSecAttrIsPermanent=false, then use SecKeyCopyAttributes to get the
     * public key's attributes including kSecAttrKeyData which contains the
     * DER-encoded SubjectPublicKeyInfo from which we extract the coordinates.
     */
    fun generateP256PublicKeyPair(): Pair<ByteArray, ByteArray> = owning { own ->
        val bits = own(number(256))

        // Create a temporary key (isPermanent=false) in the Secure Enclave
        // The key exists only in memory and is not written to the keychain
        val privateKeyAttrs = own(dictionary(isPermanent to falseValue))

        val parameters = own(
            dictionary(
                keyType to ecKeyType,
                keySizeInBits to bits,
                tokenId to secureEnclave,
                privateKeyAttributes to privateKeyAttrs,
            ),
        )
        val key = call("SecKeyCreateRandomKey") { error -> sec.SecKeyCreateRandomKey(parameters, error) }
        try {
            publicKeyCoordinates(key)
        } finally {
            release(key)
        }
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

    /**
     * The enclave key [handle] ([tokenObjectIdOf]) refers to. A key under access control asks
     * its user through [authentication], an `LAContext` ([MacLocalAuthentication]), when it is
     * used: the system shows its prompt then, saying the context's reason. The caller releases it.
     */
    fun restoreEnclaveKey(handle: ByteArray, authentication: Pointer? = null): Pointer = owning { own ->
        val data = own(data(handle))
        val entries = mutableListOf(
            keyType to ecKeyType,
            keyClass to privateKeyClass,
            tokenId to secureEnclave,
            tokenObjectId to data,
        )
        if (authentication != null) entries += authenticationContext to authentication
        val attributes = own(dictionary(*entries.toTypedArray()))
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
     * CF's copy is zeroed here. With a key under access control it blocks while the system's
     * prompt is up; a prompt that does not pass fails it with LocalAuthentication's error
     * ([SecurityCallFailed.localAuthentication]), at the top or underneath.
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
        val cfError = error.value ?: throw SecurityCallFailed(name, emptyList())
        try {
            throw SecurityCallFailed(name, chainOf(cfError))
        } finally {
            cf.CFRelease(cfError)
        }
    }

    /** [cfError]'s domain and code, then its underlying error's, and so on, a few deep. */
    private fun chainOf(cfError: Pointer): List<SecurityCallFailed.Cause> {
        val chain = mutableListOf<SecurityCallFailed.Cause>()
        var current: Pointer? = cfError
        var userInfo: Pointer? = null
        try {
            // Only a CFError is read as one: anything else under the key would crash the process.
            while (current != null && chain.size < MAX_UNDERLYING && isError(current)) {
                val domain = cf.CFErrorGetDomain(current)?.let(::stringOf)
                chain += SecurityCallFailed.Cause(domain, cf.CFErrorGetCode(current))
                // The underlying error belongs to the user info, which is released only once it is read.
                val info = cf.CFErrorCopyUserInfo(current)
                userInfo?.let(cf::CFRelease)
                userInfo = info
                current = info?.let { cf.CFDictionaryGetValue(it, underlyingError) }
            }
        } finally {
            userInfo?.let(cf::CFRelease)
        }
        return chain
    }

    private fun isError(cfObject: Pointer): Boolean = cf.CFGetTypeID(cfObject) == cf.CFErrorGetTypeID()

    private fun stringOf(string: Pointer): String? {
        val buffer = Memory(STRING_BYTES)
        return if (cf.CFStringGetCString(string, buffer, STRING_BYTES, UTF8) != 0.toByte()) {
            buffer.getString(0, "UTF-8")
        } else {
            null
        }
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

    /**
     * The x and y coordinates of the public key for P-256 [key], as 32-byte arrays each.
     * The key must be a P-256 private key from the Secure Enclave; its public half is
     * extracted to get the coordinates.
     *
     * On macOS, we use SecKeyCopyExternalRepresentation which returns the DER-encoded
     * SubjectPublicKeyInfo for EC keys. We parse this to extract the x and y coordinates.
     */
    private fun publicKeyCoordinates(key: Pointer): Pair<ByteArray, ByteArray> = owning { own ->
        val publicKey = own(checkNotNull(sec.SecKeyCopyPublicKey(key)) { "SecKeyCopyPublicKey failed" })

        // Use SecKeyCopyExternalRepresentation to get the public key's raw bytes
        val error = PointerByReference()
        val externalRep = sec.SecKeyCopyExternalRepresentation(publicKey, error)
        check(externalRep != null) {
            val cfError = error.value ?: error("SecKeyCopyExternalRepresentation failed with no error")
            try {
                throw SecurityCallFailed("SecKeyCopyExternalRepresentation", chainOf(cfError))
            } finally {
                cf.CFRelease(cfError)
            }
        }

        try {
            // externalRep is a CFData containing the DER-encoded SubjectPublicKeyInfo
            check(cf.CFGetTypeID(externalRep) == cf.CFDataGetTypeID()) {
                "external representation should be a CFData"
            }

            val keyDataBytes = bytesOf(externalRep)

            // For P-256, the external representation is a DER-encoded SubjectPublicKeyInfo
            // which is a SEQUENCE containing AlgorithmIdentifier and BIT STRING
            // The BIT STRING contains: 0x04 || x || y (uncompressed point)

            var offset = 0

            fun readLength(): Int {
                val firstByte = keyDataBytes[offset++].toInt() and 0xFF
                return if (firstByte < 0x80) {
                    firstByte
                } else if (firstByte == 0x80) {
                    error("Indefinite length not supported")
                } else {
                    val numLengthBytes = firstByte and 0x7F
                    var length = 0
                    for (i in 0 until numLengthBytes) {
                        length = (length shl 8) or (keyDataBytes[offset++].toInt() and 0xFF)
                    }
                    length
                }
            }

            // Skip SEQUENCE header
            check(keyDataBytes[offset].toInt() and 0xFF == 0x30) { "Expected SEQUENCE" }
            offset++
            val sequenceLength = readLength()
            val sequenceEnd = offset + sequenceLength

            // Skip AlgorithmIdentifier (SEQUENCE with OID and parameters)
            check(keyDataBytes[offset].toInt() and 0xFF == 0x30) { "Expected AlgorithmIdentifier SEQUENCE" }
            offset++
            val algoLength = readLength()
            offset += algoLength

            // Now at BIT STRING
            check(keyDataBytes[offset].toInt() and 0xFF == 0x03) { "Expected BIT STRING" }
            offset++
            val bitStringLength = readLength()

            // Skip unused bits byte (usually 0)
            check(bitStringLength >= 66) { "BIT STRING too short, expected at least 66 bytes" }
            val dataOffset = offset + 1

            // Verify uncompressed point marker
            check(keyDataBytes[dataOffset].toInt() and 0xFF == 0x04) {
                "Expected uncompressed point marker 0x04, got ${keyDataBytes[dataOffset].toInt().and(0xFF)}"
            }

            val x = keyDataBytes.copyOfRange(dataOffset + 1, dataOffset + 33)
            val y = keyDataBytes.copyOfRange(dataOffset + 33, dataOffset + 65)
            return@owning x to y
        } finally {
            cf.CFRelease(externalRep)
        }
    }

    companion object {
        private const val CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        private const val SECURITY = "/System/Library/Frameworks/Security.framework/Security"
        private const val UTF8 = 0x08000100 // kCFStringEncodingUTF8
        private const val SINT32_TYPE = 3L // kCFNumberSInt32Type
        private const val STRING_BYTES = 256L
        private const val MAX_UNDERLYING = 4
        private const val BIOMETRY_CURRENT_SET = 1L shl 3 // kSecAccessControlBiometryCurrentSet
        private const val PRIVATE_KEY_USAGE = 1L shl 30 // kSecAccessControlPrivateKeyUsage

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

/**
 * A Security.framework [call] that failed, with its error's domain and code and those of the
 * errors underneath it ([causes], the top one first, empty when it gave none); never key material.
 */
internal class SecurityCallFailed(val call: String, val causes: List<Cause>) : IllegalStateException(
    "$call failed (${causes.joinToString { "${it.code} in ${it.domain}" }.ifEmpty { "no error" }})",
) {

    /** One error of the chain. */
    data class Cause(val domain: String?, val code: Long)

    /**
     * LocalAuthentication's code ([LaError]) when the system's prompt is what failed the call,
     * found at the top or underneath; Security's own "the user cancelled" reads as
     * [LaError.USER_CANCEL].
     */
    val localAuthentication: Long?
        get() = causes.firstOrNull { it.domain == LOCAL_AUTHENTICATION_DOMAIN }?.code
            ?: LaError.USER_CANCEL.takeIf { Cause(OS_STATUS_DOMAIN, ERR_SEC_USER_CANCELED) in causes }

    private companion object {
        const val LOCAL_AUTHENTICATION_DOMAIN = "com.apple.LocalAuthentication"
        const val OS_STATUS_DOMAIN = "NSOSStatusErrorDomain"
        const val ERR_SEC_USER_CANCELED = -128L
    }
}
