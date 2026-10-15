package eth.serotine.domain.usecase

import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import kotlinx.coroutines.flow.Flow

/** The embedded light client's state, as a stream the Home screen shows while it starts. */
class ObserveLightClient(private val lightClient: LightClient) {
    operator fun invoke(): Flow<LightClientStatus> = lightClient.observeStatus()
}
