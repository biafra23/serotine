package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiWords
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SentAction

/**
 * What a swap paid the account, from its verified receipt's logs (docs/swaps.md, *The record*):
 * a token bought, its `Transfer` logs to the account, emitted by that token's own contract; ETH
 * bought, the pinned WETH9's `Withdrawal` logs from the router, since unwrapped ETH comes with no
 * log of its own. Only the logs of the contract a record names count: a log is a claim of the
 * contract that emits it, and any token on the route could emit one.
 */
internal object SwapProceeds {
    /** `Transfer(address,address,uint256)`'s signature hash, pinned (`SwapProceedsTest` checks it). */
    internal const val TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

    /** `Withdrawal(address,uint256)`'s signature hash, pinned (`SwapProceedsTest` checks it). */
    internal const val WITHDRAWAL = "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65"

    /**
     * What [account] received of [swap]'s bought asset, summed over the matching [logs]; null when
     * none matches (or the record names no WETH9, for ETH bought), or one's amount is no word.
     */
    fun receivedOf(swap: SentAction.Swap, account: Address, logs: List<ReceiptLog>): UInt256? {
        val paid = when (val buy = swap.buy) {
            is TransferAsset.Erc20Token -> logs.filter {
                it.address == buy.token.address && it.topics.size == 3 &&
                    it.topics[0] == TRANSFER && it.topics[2] == topicOf(account)
            }
            TransferAsset.Native -> {
                val weth = swap.weth ?: return null
                logs.filter {
                    it.address == weth && it.topics.size == 2 &&
                        it.topics[0] == WITHDRAWAL && it.topics[1] == topicOf(swap.router)
                }
            }
        }
        if (paid.isEmpty()) return null
        return paid.fold(UInt256.ZERO) { sum, log ->
            sum.plusOrNull(amountOf(log.data) ?: return null) ?: return null
        }
    }

    /** An address as an indexed topic: its ABI word. */
    private fun topicOf(address: Address): String = "0x" + AbiWords.word(address).toHex()

    /** A log's data as one uint256 word, or null when it is anything else. */
    private fun amountOf(data: String): UInt256? =
        AbiWords.returnedWord(data.removePrefix("0x"))?.let(AbiWords::uint)
}
