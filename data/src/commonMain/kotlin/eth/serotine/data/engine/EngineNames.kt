package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [NameResolver] over the engine's ENS resolution ([TransactingChain.resolveName]), against
 * beacon-finalized state only: an answer the engine did not verify there (a peer's head) is no
 * answer. The name is lower-cased and must be dot-separated non-empty labels without spaces;
 * the full normalization (ENSIP-15) is the engine's. On [dispatcher]; a device without an
 * engine, or an engine that fails, has no answer.
 */
class EngineNames internal constructor(
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
) : NameResolver {

    override suspend fun resolve(name: String): NameResolution {
        val normalized = name.trim().lowercase()
        if (!looksLikeAName(normalized)) return NameResolution.NotAName
        val chain = chain ?: return NameResolution.Unavailable(ReadUnavailable.NO_ENGINE)
        return withContext(dispatcher) {
            try {
                whyUnreadable(chain.snapshot())?.let { return@withContext NameResolution.Unavailable(it) }
                val answer = chain.resolveName(normalized)
                when {
                    answer.error != null || !answer.verified -> NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
                    answer.addressHex == null -> NameResolution.NoAddress
                    else -> Address.parse(answer.addressHex)
                        ?.let { NameResolution.Resolved(normalized, it, answer.blockNumber) }
                        ?: NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
                }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive() // as for every engine call
                NameResolution.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }
    }

    private fun looksLikeAName(name: String): Boolean {
        val labels = name.split('.')
        return labels.size >= 2 && labels.all { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }
}
