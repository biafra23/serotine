package eth.serotine.data.cow

import io.ktor.client.engine.HttpClientEngine

/**
 * The HTTP engine CoW's order API is reached through on this platform: one that connects once and
 * retries nothing behind the caller's back ([HttpCowApi]).
 */
internal expect fun cowHttpEngine(): HttpClientEngine
