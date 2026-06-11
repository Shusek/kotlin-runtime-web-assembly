package uk.shusek.krwa.log

import com.google.errorprone.annotations.FormatMethod
import java.util.function.Supplier

// For convenience, this is inspired by java.lang.System.Logger.Level.
interface Logger {
    enum class Level(private val severity: Int) {
        ALL(Int.MIN_VALUE),
        TRACE(400),
        DEBUG(500),
        INFO(800),
        WARNING(900),
        ERROR(1000),
        OFF(Int.MAX_VALUE);

        /**
         * Returns the name of this level.
         *
         * @return this level [name].
         */
        fun getName(): String = name

        /**
         * Returns the severity of this level. A higher severity means a more severe condition.
         *
         * @return this level severity.
         */
        fun getSeverity(): Int = severity
    }

    fun log(level: Level, msg: String, throwable: Throwable?)

    fun isLoggable(level: Level): Boolean

    fun trace(msg: String) {
        if (isLoggable(Level.TRACE)) {
            log(Level.TRACE, msg, null)
        }
    }

    fun trace(msgSupplier: Supplier<String>) {
        if (isLoggable(Level.TRACE)) {
            log(Level.TRACE, msgSupplier.get(), null)
        }
    }

    fun trace(msgSupplier: Supplier<String>, throwable: Throwable?) {
        if (isLoggable(Level.TRACE)) {
            log(Level.TRACE, msgSupplier.get(), throwable)
        }
    }

    @FormatMethod
    fun tracef(format: String, vararg args: Any?) {
        if (isLoggable(Level.TRACE)) {
            log(Level.TRACE, formatMessage(format, args), null)
        }
    }

    fun debug(msg: String) {
        if (isLoggable(Level.DEBUG)) {
            log(Level.DEBUG, msg, null)
        }
    }

    fun debug(msgSupplier: Supplier<String>) {
        if (isLoggable(Level.DEBUG)) {
            log(Level.DEBUG, msgSupplier.get(), null)
        }
    }

    fun debug(msgSupplier: Supplier<String>, throwable: Throwable?) {
        if (isLoggable(Level.DEBUG)) {
            log(Level.DEBUG, msgSupplier.get(), throwable)
        }
    }

    @FormatMethod
    fun debugf(format: String, vararg args: Any?) {
        if (isLoggable(Level.DEBUG)) {
            log(Level.DEBUG, formatMessage(format, args), null)
        }
    }

    fun info(msg: String) {
        if (isLoggable(Level.INFO)) {
            log(Level.INFO, msg, null)
        }
    }

    fun info(msgSupplier: Supplier<String>) {
        if (isLoggable(Level.INFO)) {
            log(Level.INFO, msgSupplier.get(), null)
        }
    }

    fun info(msgSupplier: Supplier<String>, throwable: Throwable?) {
        if (isLoggable(Level.INFO)) {
            log(Level.INFO, msgSupplier.get(), throwable)
        }
    }

    @FormatMethod
    fun infof(format: String, vararg args: Any?) {
        if (isLoggable(Level.INFO)) {
            log(Level.INFO, formatMessage(format, args), null)
        }
    }

    fun warn(msg: String) {
        if (isLoggable(Level.WARNING)) {
            log(Level.WARNING, msg, null)
        }
    }

    fun warn(msgSupplier: Supplier<String>) {
        if (isLoggable(Level.WARNING)) {
            log(Level.WARNING, msgSupplier.get(), null)
        }
    }

    fun warn(msgSupplier: Supplier<String>, throwable: Throwable?) {
        if (isLoggable(Level.WARNING)) {
            log(Level.WARNING, msgSupplier.get(), throwable)
        }
    }

    @FormatMethod
    fun warnf(format: String, vararg args: Any?) {
        if (isLoggable(Level.WARNING)) {
            log(Level.WARNING, formatMessage(format, args), null)
        }
    }

    fun error(msg: String) {
        if (isLoggable(Level.ERROR)) {
            log(Level.ERROR, msg, null)
        }
    }

    fun error(msgSupplier: Supplier<String>) {
        if (isLoggable(Level.ERROR)) {
            log(Level.ERROR, msgSupplier.get(), null)
        }
    }

    fun error(msgSupplier: Supplier<String>, throwable: Throwable?) {
        if (isLoggable(Level.ERROR)) {
            log(Level.ERROR, msgSupplier.get(), throwable)
        }
    }

    @FormatMethod
    fun errorf(format: String, vararg args: Any?) {
        if (isLoggable(Level.ERROR)) {
            log(Level.ERROR, formatMessage(format, args), null)
        }
    }

    companion object {
        private fun formatMessage(format: String, args: Array<out Any?>): String =
            if (args.isEmpty()) format else String.format(format, *args)
    }
}
