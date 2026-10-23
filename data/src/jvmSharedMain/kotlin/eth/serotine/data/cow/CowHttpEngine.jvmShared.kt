package eth.serotine.data.cow

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/** Ktor's own CIO engine, on Android and the desktop: one connection attempt per request, no OkHttp beneath it. */
internal actual fun cowHttpEngine(): HttpClientEngine = CIO.create {
    endpoint.connectAttempts = 1
}
