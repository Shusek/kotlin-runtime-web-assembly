package uk.shusek.krwa.log

open class BasicLogger : Logger {
    override fun log(level: Logger.Level, msg: String, throwable: Throwable?) {
        LOGGER.log(toJavaLoggerLevel(level), msg, throwable)
    }

    override fun isLoggable(level: Logger.Level): Boolean =
        LOGGER.isLoggable(toJavaLoggerLevel(level))

    private fun toJavaLoggerLevel(level: Logger.Level): java.util.logging.Level =
        when (level) {
            Logger.Level.ALL -> java.util.logging.Level.ALL
            Logger.Level.TRACE -> java.util.logging.Level.FINEST
            Logger.Level.DEBUG -> java.util.logging.Level.FINE
            Logger.Level.INFO -> java.util.logging.Level.INFO
            Logger.Level.WARNING -> java.util.logging.Level.WARNING
            Logger.Level.ERROR -> java.util.logging.Level.SEVERE
            Logger.Level.OFF -> java.util.logging.Level.OFF
        }

    private companion object {
        private val LOGGER = java.util.logging.Logger.getLogger("krwa")
    }
}
