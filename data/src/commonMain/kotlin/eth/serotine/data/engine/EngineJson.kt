package eth.serotine.data.engine

import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The engine's JSON answers (fee history, receipts), read strictly: a field missing or malformed is no answer. */
internal object EngineJson {

    /** The fee levels' percentiles, in [FeeLevel] order. */
    val PERCENTILES = doubleArrayOf(10.0, 50.0, 90.0)

    /**
     * A [FeeQuote] from an `eth_feeHistory` answer asked with [PERCENTILES]: the next base fee is
     * the history's last `baseFeePerGas` (the one after its newest block), and each level's tip
     * the median, over the blocks, of its percentile's reward, never below [MIN_TIP] and never
     * below the slower level's. Null when the answer is not one.
     */
    fun feeQuote(json: String): FeeQuote? = try {
        quoteOf(Json.parseToJsonElement(json).jsonObject)
    } catch (e: Exception) {
        null
    }

    private fun quoteOf(root: JsonObject): FeeQuote? {
        val baseFees = root.getValue("baseFeePerGas").jsonArray.map { quantity(it) ?: return null }
        val oldest = quantity(root.getValue("oldestBlock"))?.toLongOrNull() ?: return null
        val rewards = root["reward"]?.jsonArray.orEmpty().map { block ->
            block.jsonArray.map { quantity(it) ?: return null }
        }
        if (baseFees.isEmpty() || rewards.any { it.size != PERCENTILES.size }) return null
        val newest = oldest + baseFees.size - 2
        var floor = MIN_TIP
        val tips = FeeLevel.entries.mapIndexed { index, level ->
            val paid = rewards.map { UInt256.of(it[index]) }.sorted()
            val median = if (paid.isEmpty()) MIN_TIP else paid[paid.size / 2]
            val tip = maxOf(median, floor)
            floor = tip
            level to tip
        }.toMap()
        return FeeQuote(UInt256.of(baseFees.last()), tips, newest)
    }

    /** A receipt's verdict: null for the "null" literal (no verified block includes it yet). */
    fun receipt(json: String): Receipt? {
        if (json.trim() == "null") return null
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("not a receipt")
        val block = quantity(root.getValue("blockNumber"))?.toLongOrNull() ?: throw IllegalArgumentException("no block")
        val status = quantity(root.getValue("status")) ?: throw IllegalArgumentException("no status")
        return Receipt(block, succeeded = status == "1")
    }

    /** A receipt's block and outcome. */
    data class Receipt(val blockNumber: Long, val succeeded: Boolean)

    /** A JSON-RPC quantity (`0x`-hex) as a decimal string, or null when it is not one. */
    private fun quantity(element: JsonElement): String? {
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
        if (!text.startsWith("0x") || text.length < 3 || text.length > 66) return null
        val digits = text.substring(2)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val even = digits.padStart(digits.length + digits.length % 2, '0')
        return UInt256.fromBigEndian(even.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).toDecimal()
    }

    /** The least tip a level offers: 0.001 gwei, so that a fee history of empty blocks still pays something. */
    val MIN_TIP: UInt256 = UInt256.of(1_000_000L)
}
