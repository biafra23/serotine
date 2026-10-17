package eth.serotine.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One change to the wallet's keys and accounts at a time. The use cases that touch the key
 * store and the account list together ([ImportMnemonicAccount], [RemoveAccount]) each run
 * under it, so neither can interleave with the other: otherwise a removal could delete a key
 * an import has just stored for the same address, and leave an account whose key is gone.
 * The composition root makes one for the whole app and hands the same one to every such use
 * case; two instances would not serialize anything.
 */
class WalletChanges {
    private val mutex = Mutex()

    /** Runs [change] once no other change is running. */
    suspend fun <T> serialized(change: suspend () -> T): T = mutex.withLock { change() }
}
