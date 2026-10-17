package eth.serotine.domain.usecase

import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import kotlinx.coroutines.flow.Flow

/** The embedded light client's state, as a stream the Assets tab and Settings show. */
class ObserveLightClient(private val lightClient: LightClient) {
    operator fun invoke(): Flow<LightClientStatus> = lightClient.observeStatus()
}
