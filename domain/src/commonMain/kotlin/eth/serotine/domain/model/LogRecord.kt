package eth.serotine.domain.model

/**
 * How much a [LogRecord] says, least first: a log set to a level records that level and every
 * one above it. [INFO] says what happened and how it ended; [DEBUG] adds what went in and what
 * came back.
 */
enum class LogLevel { DEBUG, INFO }

/**
 * One line of the app's debug log: what [source] did ([message]), at [epochMillis] (unix
 * milliseconds), on [level]. [id] orders the records of one log and tells two apart that say
 * the same. A record holds public chain data and the app's own state only: never key material,
 * never a password or a recovery phrase.
 */
data class LogRecord(
    val id: Long,
    val epochMillis: Long,
    val level: LogLevel,
    val source: String,
    val message: String,
)
