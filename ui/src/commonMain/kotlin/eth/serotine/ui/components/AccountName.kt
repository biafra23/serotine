package eth.serotine.ui.components

import eth.serotine.domain.model.Account
import eth.serotine.domain.usecase.EnsNameCheck

/**
 * The name [account] is shown by, its EIP-55 [address] given: its label, unless that is the
 * ENS name it follows ([Account.ensName]) and [check] found the name no longer points to it,
 * when it is the short address (issue #67). Until a check says so, the name stands.
 */
internal fun accountName(account: Account, address: String, check: EnsNameCheck?): String =
    if (account.ensName != null && check == EnsNameCheck.Moved) shortAddress(address) else account.label

/** What is said of the ENS name [account] follows once [check] found it no longer points to it; else null. */
internal fun ensNameNote(account: Account, check: EnsNameCheck?): String? =
    account.ensName?.takeIf { check == EnsNameCheck.Moved }
        ?.let { "ENS name “$it” no longer points to this address" }
