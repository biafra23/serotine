package eth.serotine.data.cow

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/** NSURLSession beneath Ktor's Darwin engine, on iOS. */
internal actual fun cowHttpEngine(): HttpClientEngine = Darwin.create()
