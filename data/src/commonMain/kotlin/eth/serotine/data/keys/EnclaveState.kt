package eth.serotine.data.keys

import kotlinx.serialization.Serializable

/**
 * The state file of a Secure Enclave secret (the Mac's MacSecureEnclaveSecret, iOS's
 * IosSecureEnclaveSecret): the enclave key's handle and `S_hw` encrypted to it, both hex.
 */
@Serializable
internal class EnclaveState(val version: Int, val key: String, val secret: String)
