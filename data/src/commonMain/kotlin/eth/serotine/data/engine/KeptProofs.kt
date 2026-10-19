package eth.serotine.data.engine

/**
 * Where [ProvenReads] keeps the last proof of a key beyond the process, so that after a restart
 * the reading can still say what was last verified ([EngineReading.LastKnown]) until a head can
 * be read again. [none] keeps nothing; the balance adapters keep theirs in a [LastBalancesFile].
 */
internal interface KeptProofs<K, T> {

    /** The last proof of [key] kept, or null when none is (or it cannot be read). Never throws. */
    suspend fun load(key: K): EngineReading.LastKnown<T>?

    /**
     * Keeps [proof] as the last proof of [key]; one this store has no use for is let go. A
     * failure to keep it is the store's to report, not the caller's. Never throws.
     */
    suspend fun save(key: K, proof: EngineReading.LastKnown<T>)

    companion object {
        /** Keeps nothing: a restart starts from [EngineReading.Syncing]. */
        fun <K, T> none(): KeptProofs<K, T> = object : KeptProofs<K, T> {
            override suspend fun load(key: K): EngineReading.LastKnown<T>? = null

            override suspend fun save(key: K, proof: EngineReading.LastKnown<T>) = Unit
        }
    }
}
