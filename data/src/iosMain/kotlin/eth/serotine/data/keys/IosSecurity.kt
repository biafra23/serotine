package eth.serotine.data.keys

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataGetTypeID
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecKeyCopyAttributes
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.Security.kSecPrivateKeyAttrs
import platform.posix.memset

/**
 * The few CoreFoundation and Security.framework calls [IosSecureEnclaveSecret] needs: the iOS
 * twin of the desktop's MacSecurity, the same calls through Kotlin/Native's bindings instead of
 * JNA. A failing call throws an [IllegalStateException] naming the call and its error code,
 * never key material.
 *
 * As on the Mac, the enclave key's handle is read and restored through its token object id, the
 * attribute `"toid"` CryptoKit's `SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation:)`
 * stands on: the key is not permanent, so it needs no keychain item. On iOS the app's sandbox is
 * what keeps other apps from the handle.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosSecurity {

    private val tokenObjectId: CFStringRef =
        checkNotNull(CFStringCreateWithCString(null, "toid", kCFStringEncodingUTF8)) { "CFString" }

    /**
     * A new P-256 key inside the Secure Enclave, kept nowhere but in its own handle
     * ([tokenObjectIdOf]), usable while the device is unlocked, on this device only, and with no
     * user-authentication requirement (the envelope's `HW_pw`): the access control CryptoKit
     * gives such a key by default. The caller releases it.
     */
    fun createEnclaveKey(): SecKeyRef = owning {
        val access = own(
            call("SecAccessControlCreateWithFlags") { error ->
                SecAccessControlCreateWithFlags(
                    null,
                    kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                    kSecAccessControlPrivateKeyUsage,
                    error,
                )
            },
        )
        val privateAttributes = own(dictionary(kSecAttrIsPermanent to kCFBooleanFalse, kSecAttrAccessControl to access))
        val bits = own(number(256))
        val parameters = own(
            dictionary(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits to bits,
                kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                kSecPrivateKeyAttrs to privateAttributes,
            ),
        )
        call("SecKeyCreateRandomKey") { error -> SecKeyCreateRandomKey(parameters, error) }
    }

    /** The enclave's encrypted handle to [key]: useless anywhere but in this device's Secure Enclave. */
    fun tokenObjectIdOf(key: SecKeyRef): ByteArray {
        val attributes = checkNotNull(SecKeyCopyAttributes(key)) { "SecKeyCopyAttributes failed" }
        try {
            val handle = checkNotNull(CFDictionaryGetValue(attributes, tokenObjectId)) {
                "the key has no token object id"
            }
            check(CFGetTypeID(handle) == CFDataGetTypeID()) { "the key's token object id is not data" }
            return bytesOf(handle.reinterpret()) // owned by the attributes dictionary, so copied before it goes
        } finally {
            CFRelease(attributes)
        }
    }

    /** The enclave key [handle] ([tokenObjectIdOf]) refers to. The caller releases it. */
    fun restoreEnclaveKey(handle: ByteArray): SecKeyRef = owning {
        val data = own(data(handle))
        val attributes = own(
            dictionary(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeyClass to kSecAttrKeyClassPrivate,
                kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                tokenObjectId to data,
            ),
        )
        call("SecKeyCreateWithData") { error -> SecKeyCreateWithData(data, attributes, error) }
    }

    /** [plaintext] encrypted to [privateKey]'s public half with ECIES (X9.63 KDF, AES-GCM); CF's copy is zeroed. */
    fun eciesEncrypt(privateKey: SecKeyRef, plaintext: ByteArray): ByteArray = owning {
        val publicKey = own(checkNotNull(SecKeyCopyPublicKey(privateKey)) { "SecKeyCopyPublicKey failed" })
        val input = own(data(plaintext))
        try {
            val output = own(
                call("SecKeyCreateEncryptedData") { error ->
                    SecKeyCreateEncryptedData(
                        publicKey,
                        kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM,
                        input,
                        error,
                    )
                },
            )
            bytesOf(output)
        } finally {
            zero(input)
        }
    }

    /**
     * [ciphertext] decrypted by the enclave with [privateKey]. The caller zeroes the result; CF's
     * copy is zeroed here.
     */
    fun eciesDecrypt(privateKey: SecKeyRef, ciphertext: ByteArray): ByteArray = owning {
        val input = own(data(ciphertext))
        val output = own(
            call("SecKeyCreateDecryptedData") { error ->
                SecKeyCreateDecryptedData(
                    privateKey,
                    kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM,
                    input,
                    error,
                )
            },
        )
        try {
            bytesOf(output)
        } finally {
            zero(output)
        }
    }

    fun release(cf: CFTypeRef) = CFRelease(cf)

    /** The CF objects an [owning] block made, released when it ends. */
    private class Owner {
        val objects = mutableListOf<CFTypeRef>()

        /** Hands [cf] to this owner and gives it back. */
        fun <T : CPointer<*>> own(cf: T): T = cf.also { objects.add(it) }
    }

    /**
     * Runs [block], releasing every CF object it hands to `own` when it ends, however it ends: no
     * failure path leaks one. What [block] returns is not released.
     */
    private inline fun <T> owning(block: Owner.() -> T): T {
        val owner = Owner()
        try {
            return owner.block()
        } finally {
            owner.objects.asReversed().forEach { CFRelease(it) }
        }
    }

    private inline fun <T : CPointer<*>> call(name: String, block: (CPointer<CFErrorRefVar>) -> T?): T = memScoped {
        val error = alloc<CFErrorRefVar>()
        val result = block(error.ptr)
        if (result != null) return@memScoped result
        val code = error.value?.let { cfError -> CFErrorGetCode(cfError).also { CFRelease(cfError) } }
        error("$name failed (${code ?: "no error"})")
    }

    private fun dictionary(vararg entries: Pair<CPointer<*>?, CPointer<*>?>): CFMutableDictionaryRef {
        val dictionary = checkNotNull(
            CFDictionaryCreateMutable(
                null,
                entries.size.convert(),
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        ) { "CFDictionaryCreateMutable failed" }
        for ((key, value) in entries) CFDictionarySetValue(dictionary, key, value)
        return dictionary
    }

    private fun number(value: Int): CFTypeRef = memScoped {
        val number = alloc<IntVar>()
        number.value = value
        checkNotNull(CFNumberCreate(null, kCFNumberSInt32Type, number.ptr)) { "CFNumberCreate failed" }
    }

    /** A CFData copy of [bytes]: CF copies them out of the pinned array, so no other copy is made here. */
    private fun data(bytes: ByteArray): CFDataRef {
        val created = if (bytes.isEmpty()) {
            CFDataCreate(null, null, 0)
        } else {
            bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert()) }
        }
        return checkNotNull(created) { "CFDataCreate failed" }
    }

    private fun bytesOf(data: CFDataRef): ByteArray {
        val length = CFDataGetLength(data).toInt()
        if (length == 0) return ByteArray(0)
        return checkNotNull(CFDataGetBytePtr(data)) { "CFDataGetBytePtr failed" }.readBytes(length)
    }

    /**
     * Overwrites [data]'s bytes before it is released: CF frees its buffers without zeroing them.
     * The CFData is immutable by contract; writing through its byte pointer is the only way to
     * reach that copy, as on the Mac.
     */
    private fun zero(data: CFDataRef) {
        val length = CFDataGetLength(data)
        if (length == 0L) return
        CFDataGetBytePtr(data)?.let { memset(it, 0, length.convert()) }
    }
}
