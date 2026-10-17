package eth.serotine.domain.usecase

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Each value this flow emits, with the latest reading of each of the [keys] it names: one
 * reader per key, started when its key arrives and cancelled when it leaves, so the other
 * readers go on undisturbed and none waits for a cancelled read in flight to end (a verified
 * read can take long). A key has no entry until its first reading; what a cancelled reader
 * still delivers is dropped, so a key named again starts from no entry. The upstream is
 * collected once.
 */
internal fun <T, K, V> Flow<T>.readEach(keys: (T) -> Set<K>, read: (K) -> Flow<V>): Flow<Pair<T, Map<K, V>>> =
    channelFlow {
        val readings = MutableStateFlow<Map<K, V>>(emptyMap())
        // Touched only where the upstream is collected, one value at a time.
        val readers = mutableMapOf<K, Job>()
        val values = onEach { value ->
            val wanted = keys(value)
            readers.keys.filterNot { it in wanted }.forEach { key ->
                readers.remove(key)?.cancel() // not joined
                readings.update { it - key }
            }
            wanted.filterNot { it in readers }.forEach { key ->
                readers[key] = launch {
                    read(key).collect { reading ->
                        // Checked inside the update: its removal comes after the cancellation,
                        // so a write racing it is retried, and then sees the reader cancelled.
                        readings.update { if (isActive) it + (key to reading) else it }
                    }
                }
            }
        }
        combine(values, readings) { value, read ->
            val wanted = keys(value)
            value to read.filterKeys { it in wanted }
        }.collect { send(it) }
    }
