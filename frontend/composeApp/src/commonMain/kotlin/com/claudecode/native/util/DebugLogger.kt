package com.claudecode.native.util

/**
 * Simple debug logging utility with log level management.
 * Logs are only printed when debug mode is enabled.
 */
object DebugLogger {
    /**
     * Log level enum for filtering messages.
     */
    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * Enable/disable debug logging globally.
     * Set to false in production builds.
     */
    var enabled: Boolean = true

    /**
     * Minimum log level to display.
     */
    var minLevel: Level = Level.DEBUG

    /**
     * Tag filter - if set, only logs with this tag will be shown.
     */
    var tagFilter: String? = null

    /**
     * Log a debug message.
     */
    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message)
    }

    /**
     * Log a debug message with lazy evaluation.
     * The message lambda is only invoked if logging is enabled and level is met.
     */
    inline fun d(tag: String, message: () -> String) {
        if (shouldLog(Level.DEBUG, tag)) {
            log(Level.DEBUG, tag, message())
        }
    }

    /**
     * Log an info message.
     */
    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message)
    }

    /**
     * Log an info message with lazy evaluation.
     */
    inline fun i(tag: String, message: () -> String) {
        if (shouldLog(Level.INFO, tag)) {
            log(Level.INFO, tag, message())
        }
    }

    /**
     * Log a warning message.
     */
    fun w(tag: String, message: String) {
        log(Level.WARN, tag, message)
    }

    /**
     * Log a warning message with lazy evaluation.
     */
    inline fun w(tag: String, message: () -> String) {
        if (shouldLog(Level.WARN, tag)) {
            log(Level.WARN, tag, message())
        }
    }

    /**
     * Log an error message.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message)
        throwable?.printStackTrace()
    }

    /**
     * Log an error message with lazy evaluation.
     */
    inline fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (shouldLog(Level.ERROR, tag)) {
            log(Level.ERROR, tag, message())
            throwable?.printStackTrace()
        }
    }

    /**
     * Check if a message should be logged based on current settings.
     */
    @PublishedApi
    internal fun shouldLog(level: Level, tag: String): Boolean {
        if (!enabled) return false
        if (level.ordinal < minLevel.ordinal) return false
        if (tagFilter != null && !tag.contains(tagFilter!!)) return false
        return true
    }

    @PublishedApi
    internal fun log(level: Level, tag: String, message: String) {
        if (!enabled) return
        if (level.ordinal < minLevel.ordinal) return
        if (tagFilter != null && !tag.contains(tagFilter!!)) return

        val prefix = when (level) {
            Level.DEBUG -> "D"
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ERROR -> "E"
        }
        println("[$prefix/$tag] $message")
    }
}

/**
 * Convenience extension for common logging patterns.
 */
fun Any.logDebug(message: String) {
    DebugLogger.d(this::class.simpleName ?: "Unknown", message)
}
