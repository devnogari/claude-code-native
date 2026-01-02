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
     * Log an info message.
     */
    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message)
    }

    /**
     * Log a warning message.
     */
    fun w(tag: String, message: String) {
        log(Level.WARN, tag, message)
    }

    /**
     * Log an error message.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message)
        throwable?.printStackTrace()
    }

    private fun log(level: Level, tag: String, message: String) {
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
