package eth.serotine.domain.model

import eth.serotine.domain.model.AbiWords.selector

/**
 * What a [SafeTx] does, decoded here from its raw fields (docs/wallet-ux.md, screen 17), never
 * from a description that came with it. Decoding is strict: a call is read as a transfer or a
 * change of the Safe's settings only when its data is exactly that function's encoding;
 * anything else is a [ContractCall] with its selector, and a delegate call is always a
 * [DelegateCall], whatever its data, because the Safe then runs the target's code as itself.
 */
sealed interface SafeTxAction {

    /** [amount] wei of the network's currency to [to], with no call data. */
    data class NativeTransfer(val to: Address, val amount: UInt256) : SafeTxAction

    /**
     * [amount] (in the token's smallest units) of the ERC-20 token at [token] to [to]: the
     * token's `transfer`, called with no ether. What the token is called is the token's
     * contract's claim, read separately; the transfer is to that contract's address.
     */
    data class TokenTransfer(val token: Address, val to: Address, val amount: UInt256) : SafeTxAction

    /** A change of the Safe's own settings: a call of one of its functions, on itself, with no ether. */
    data class SettingsChange(val change: SafeSettingsChange) : SafeTxAction

    /**
     * Nothing: a call of the Safe itself with no ether and no data. Executed, it uses up its
     * nonce, which is how owners reject a transaction queued at the same nonce.
     */
    data object Rejection : SafeTxAction

    /**
     * A call of the contract at [to] with [value] wei and [dataSize] bytes of data, which this
     * wallet does not decode; [selector] is the function's (8 lower-case hex digits), null when
     * the data is shorter than one.
     */
    data class ContractCall(val to: Address, val value: UInt256, val selector: String?, val dataSize: Int) :
        SafeTxAction

    /**
     * A delegate call of [to]: the Safe runs that contract's code as itself, with its storage,
     * owners and funds, and the code can change any of them. Never decoded further: the data
     * means whatever that code makes of it. [selector] and [dataSize] as for [ContractCall].
     */
    data class DelegateCall(val to: Address, val selector: String?, val dataSize: Int) : SafeTxAction

    companion object {
        private val ADD_OWNER = selector("addOwnerWithThreshold(address,uint256)")
        private val REMOVE_OWNER = selector("removeOwner(address,address,uint256)")
        private val SWAP_OWNER = selector("swapOwner(address,address,address)")
        private val CHANGE_THRESHOLD = selector("changeThreshold(uint256)")
        private val ENABLE_MODULE = selector("enableModule(address)")
        private val DISABLE_MODULE = selector("disableModule(address,address)")
        private val SET_GUARD = selector("setGuard(address)")
        private val SET_MODULE_GUARD = selector("setModuleGuard(address)")
        private val SET_FALLBACK_HANDLER = selector("setFallbackHandler(address)")

        /** What [tx], a transaction of the Safe at [safe], does. */
        fun of(safe: Address, tx: SafeTx): SafeTxAction {
            val data = tx.data
            val selector = data.takeIf { it.size >= AbiWords.SELECTOR }?.copyOf(AbiWords.SELECTOR)?.toHex()
            if (tx.operation == SafeOperation.DELEGATECALL) return DelegateCall(tx.to, selector, data.size)
            if (data.isEmpty()) {
                return if (tx.to == safe && tx.value.isZero) Rejection else NativeTransfer(tx.to, tx.value)
            }
            if (tx.value.isZero) {
                // A call of the Safe on itself is one of its own functions, or no transfer at all.
                if (tx.to == safe) {
                    settingsChange(data)?.let { return SettingsChange(it) }
                } else {
                    Erc20.transferOf(data)?.let { return TokenTransfer(tx.to, it.to, it.amount) }
                }
            }
            return ContractCall(tx.to, tx.value, selector, data.size)
        }

        /** The change of settings [data] makes as a call of the Safe on itself, or null when it makes none of them. */
        private fun settingsChange(data: ByteArray): SafeSettingsChange? {
            fun args(selector: ByteArray, words: Int) = AbiWords.staticArguments(data, selector, words)
            fun address(word: ByteArray) = AbiWords.address(word)
            fun count(word: ByteArray) = AbiWords.uint(word).toLongOrNull()

            args(ADD_OWNER, 2)?.let { (owner, threshold) ->
                return SafeSettingsChange.OwnerAdded(address(owner) ?: return null, count(threshold) ?: return null)
            }
            args(REMOVE_OWNER, 3)?.let { (_, owner, threshold) ->
                return SafeSettingsChange.OwnerRemoved(address(owner) ?: return null, count(threshold) ?: return null)
            }
            args(SWAP_OWNER, 3)?.let { (_, old, new) ->
                return SafeSettingsChange.OwnerSwapped(address(old) ?: return null, address(new) ?: return null)
            }
            args(CHANGE_THRESHOLD, 1)?.let { (threshold) ->
                return SafeSettingsChange.ThresholdChanged(count(threshold) ?: return null)
            }
            args(ENABLE_MODULE, 1)?.let { (module) ->
                return SafeSettingsChange.ModuleEnabled(address(module) ?: return null)
            }
            args(DISABLE_MODULE, 2)?.let { (_, module) ->
                return SafeSettingsChange.ModuleDisabled(address(module) ?: return null)
            }
            args(SET_GUARD, 1)?.let { (guard) ->
                return SafeSettingsChange.GuardSet((address(guard) ?: return null).orNone())
            }
            args(SET_MODULE_GUARD, 1)?.let { (guard) ->
                return SafeSettingsChange.ModuleGuardSet((address(guard) ?: return null).orNone())
            }
            args(SET_FALLBACK_HANDLER, 1)?.let { (handler) ->
                return SafeSettingsChange.FallbackHandlerSet((address(handler) ?: return null).orNone())
            }
            return null
        }

        private fun Address.orNone(): Address? = takeIf { it != Address.ZERO }
    }
}

/**
 * A change of a Safe's own settings, decoded from a call of the Safe on itself. The linked-list
 * neighbour some of these functions also take (`prevOwner`, `prevModule`) is a detail of the
 * Safe's storage, not part of the change, and left out.
 */
sealed interface SafeSettingsChange {

    /** [owner] becomes an owner, and the threshold [threshold]. */
    data class OwnerAdded(val owner: Address, val threshold: Long) : SafeSettingsChange

    /** [owner] is no longer an owner, and the threshold becomes [threshold]. */
    data class OwnerRemoved(val owner: Address, val threshold: Long) : SafeSettingsChange

    /** [old] is replaced as an owner by [new]. */
    data class OwnerSwapped(val old: Address, val new: Address) : SafeSettingsChange

    /** The threshold becomes [threshold]. */
    data class ThresholdChanged(val threshold: Long) : SafeSettingsChange

    /** [module] is enabled: it can move the Safe's funds without the owners' signatures. */
    data class ModuleEnabled(val module: Address) : SafeSettingsChange

    /** [module] is disabled. */
    data class ModuleDisabled(val module: Address) : SafeSettingsChange

    /** [guard] checks every transaction from now on (null: no guard any more). */
    data class GuardSet(val guard: Address?) : SafeSettingsChange

    /** [guard] checks every module's transaction from now on (null: none any more); Safe 1.5.0 on. */
    data class ModuleGuardSet(val guard: Address?) : SafeSettingsChange

    /** [handler] answers the calls the Safe has no function for (null: none any more). */
    data class FallbackHandlerSet(val handler: Address?) : SafeSettingsChange
}

/**
 * The refund a [SafeTx] makes the Safe pay whoever executes it, when its gas price is not zero
 * (docs/wallet-ux.md, screen 17): up to `(gas used + baseGas) × gasPrice` — for ether, at most
 * the gas price the execution itself pays — in [gasToken] (null: ether) to [receiver] (null:
 * the account that executes it). The gas used is the execution's and is known only once it
 * runs: with a [safeTxGas] of zero the call may use whatever gas the execution is given.
 */
data class SafeTxRefund(
    val gasPrice: UInt256,
    val baseGas: UInt256,
    val safeTxGas: UInt256,
    val gasToken: Address?,
    val receiver: Address?,
) {
    companion object {
        /** The refund [tx] makes, or null when it makes none (a gas price of zero). */
        fun of(tx: SafeTx): SafeTxRefund? = if (tx.gasPrice.isZero) {
            null
        } else {
            SafeTxRefund(
                gasPrice = tx.gasPrice,
                baseGas = tx.baseGas,
                safeTxGas = tx.safeTxGas,
                gasToken = tx.gasToken.takeIf { it != Address.ZERO },
                receiver = tx.refundReceiver.takeIf { it != Address.ZERO },
            )
        }
    }
}
