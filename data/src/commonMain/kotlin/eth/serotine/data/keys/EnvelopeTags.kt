package eth.serotine.data.keys

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/** The hardware tags of the envelopes in [directory] that can be read; the others say nothing. */
internal fun envelopeTagsIn(directory: String): Set<String> {
    val dir = Path(directory)
    if (!SystemFileSystem.exists(dir)) return emptySet()
    return SystemFileSystem.list(dir).filter { it.name.endsWith(".seed") }.mapNotNullTo(HashSet()) { file ->
        try {
            SystemFileSystem.source(file).buffered().use { source ->
                Json.decodeFromString(SealedSeed.serializer(), source.readString()).hardware
            }
        } catch (e: Exception) {
            null
        }
    }
}
