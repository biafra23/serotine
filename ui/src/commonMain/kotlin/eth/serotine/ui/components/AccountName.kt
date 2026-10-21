package eth.serotine.ui.components

import eth.serotine.domain.model.Account
import eth.serotine.domain.usecase.EnsNameCheck

/**
 * The name [account] is shown by, its EIP-55 [address] given: its label, unless that is the
 * ENS name it follows ([Account.ensName]) and [check] found the name no longer points to it,
 * or no longer verifiably, when it is the short address (issue #67). Until a check says so,
 * the name stands.
 */
internal fun accountName(account: Account, address: String, check: EnsNameCheck?): String =
    if (ensNameNote(account, check) != null) shortAddress(address) else account.label

/** Why the ENS name [account] follows is not shown as its name, once [check] found a reason; else null. */
internal fun ensNameNote(account: Account, check: EnsNameCheck?): String? {
    val name = account.ensName ?: return null
    return when (check) {
        EnsNameCheck.Moved -> "ENS name “$name” no longer points to this address"
        EnsNameCheck.Unverifiable -> "ENS name “$name” can no longer be verified to point to this address"
        is EnsNameCheck.Verified, is EnsNameCheck.Unavailable, null -> null
    }
}
