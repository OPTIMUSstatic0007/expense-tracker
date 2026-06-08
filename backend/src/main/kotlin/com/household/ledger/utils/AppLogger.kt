package com.household.ledger.utils

object AppLogger {
    private val isAndroid: Boolean by lazy {
        System.getProperty("java.vendor")?.contains("Android") == true ||
        System.getProperty("java.vm.vendor")?.contains("Android") == true
    }

    private var slf4jLogger: Any? = null

    init {
        if (!isAndroid) {
            try {
                // Initialize SLF4J ONLY if not on Android
                slf4jLogger = org.slf4j.LoggerFactory.getLogger("BackendLogger")
            } catch (e: Throwable) {
                // Fallback gracefully
            }
        }
    }

    fun info(tag: String, message: String) {
        if (isAndroid) {
            // Android-safe fallback. On Android, System.out is redirected to Logcat.
            println("I/$tag: $message")
        } else {
            val logger = slf4jLogger
            if (logger is org.slf4j.Logger) {
                logger.info("[$tag] $message")
            } else {
                println("INFO [$tag]: $message")
            }
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (isAndroid) {
            println("E/$tag: $message")
            throwable?.printStackTrace()
        } else {
            val logger = slf4jLogger
            if (logger is org.slf4j.Logger) {
                if (throwable != null) {
                    logger.error("[$tag] $message", throwable)
                } else {
                    logger.error("[$tag] $message")
                }
            } else {
                println("ERROR [$tag]: $message")
                throwable?.printStackTrace()
            }
        }
    }
}
