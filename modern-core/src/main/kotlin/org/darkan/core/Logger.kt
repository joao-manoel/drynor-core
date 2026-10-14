package org.darkan.core

import java.util.logging.ConsoleHandler
import java.util.logging.Level

object Logger {
    private const val ROOT_KEY = "DarkanRS"
    private val DARKAN_ROOT_LOGGER = java.util.logging.Logger.getLogger(ROOT_KEY);

    init {
        setupFormat()
    }

    @JvmStatic
    fun setupFormat() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1\$tF %1\$tT %1\$tL] [%4$-7s] %5\$s %n")
        DARKAN_ROOT_LOGGER.handlers.forEach { DARKAN_ROOT_LOGGER.removeHandler(it) }
        DARKAN_ROOT_LOGGER.addHandler(ConsoleHandler())
        DARKAN_ROOT_LOGGER.useParentHandlers = false
    }

    @JvmStatic
    fun setLogLevel(level: Level) {
        DARKAN_ROOT_LOGGER.level = level
        DARKAN_ROOT_LOGGER.handlers.forEach { it.level = level }
    }

    private fun getCallerInfo(): Pair<String, String> {
        val stackTrace = Thread.currentThread().stackTrace
        val callerElement = stackTrace.firstOrNull { !it.className.contains("Logger") && !it.className.contains("Thread") }
        return callerElement?.let {
            val className = it.className.substringAfterLast('.')
            Pair(className, it.methodName)
        } ?: Pair("Unknown", "unknown")
    }

    private fun formatMessage(className: String, methodName: String, msg: Any): String = "[$className.$methodName] $msg"

    private fun logToDB(className: String, methodName: String, message: String, throwable: Throwable? = null) {
        //TODO
//        DBConnection.getErrors()?.docs?.let {
//            when (throwable) {
//                null -> DBConnection.getErrors().logError("$className.$methodName", message)
//                else -> DBConnection.getErrors().logError("$className.$methodName", message, throwable)
//            }
//        }
    }

    fun Any.logErrorToDatabase(message: String, throwable: Throwable? = null) {
        val (className, methodName) = getCallerInfo()
        logToDB(className, methodName, message, throwable)
        logError(message, throwable)
    }

    fun logError(message: String, throwable: Throwable? = null) {
        val (className, methodName) = getCallerInfo()
        DARKAN_ROOT_LOGGER.log(Level.SEVERE, formatMessage(className, methodName, message), throwable)
        if (EnvVars.debug && throwable != null)
            throwable.printStackTrace()
    }

    fun Any.logSevere(msg: Any) {
        val (className, methodName) = getCallerInfo()
        DARKAN_ROOT_LOGGER.log(Level.SEVERE, formatMessage(className, methodName, msg))
    }

    fun Any.logWarn(msg: Any, throwable: Throwable? = null) {
        val (className, methodName) = getCallerInfo()
        DARKAN_ROOT_LOGGER.log(Level.WARNING, formatMessage(className, methodName, msg))
        if (EnvVars.debug && throwable != null)
            throwable.printStackTrace()
    }

    fun Any.logInfo(msg: Any) {
        val (className, methodName) = getCallerInfo()
        DARKAN_ROOT_LOGGER.log(Level.CONFIG, formatMessage(className, methodName, msg))
    }

    fun Any.logDebug(msg: Any) {
        val (className, methodName) = getCallerInfo()
        DARKAN_ROOT_LOGGER.log(Level.FINE, formatMessage(className, methodName, msg))
    }

    fun Any.logTrace(msg: Any) {
        val (className, methodName) = getCallerInfo()
        DARKAN_ROOT_LOGGER.log(Level.FINER, formatMessage(className, methodName, msg))
    }

    // --- Java-compatible @JvmStatic methods matching legacy Logger API ---

    @JvmStatic
    fun setLevel(level: Level) = setLogLevel(level)

    @JvmStatic
    fun setDebug(enabled: Boolean) {
        if (enabled) setLogLevel(Level.FINE) else setLogLevel(Level.CONFIG)
    }

    @JvmStatic
    fun handle(source: Class<*>, method: String, e: Throwable) {
        DARKAN_ROOT_LOGGER.log(Level.SEVERE, formatMessage(source.simpleName, method, e.message ?: "Exception"), e)
        if (EnvVars.debug) e.printStackTrace()
    }

    @JvmStatic
    fun handle(source: Class<*>, method: String, message: String, e: Throwable) {
        DARKAN_ROOT_LOGGER.log(Level.SEVERE, formatMessage(source.simpleName, method, message), e)
        if (EnvVars.debug) e.printStackTrace()
    }

    @JvmStatic
    fun handleNoRecord(source: Class<*>, method: String, message: String, e: Throwable) {
        DARKAN_ROOT_LOGGER.log(Level.SEVERE, formatMessage(source.simpleName, method, message), e)
        if (EnvVars.debug) e.printStackTrace()
    }

    @JvmStatic
    fun error(source: Class<*>, method: String, msg: Any) {
        DARKAN_ROOT_LOGGER.log(Level.SEVERE, formatMessage(source.simpleName, method, msg))
    }

    @JvmStatic
    fun warn(source: Class<*>, method: String, msg: Any) {
        DARKAN_ROOT_LOGGER.log(Level.WARNING, formatMessage(source.simpleName, method, msg))
    }

    @JvmStatic
    fun info(source: Class<*>, method: String, msg: Any) {
        DARKAN_ROOT_LOGGER.log(Level.CONFIG, formatMessage(source.simpleName, method, msg))
    }

    @JvmStatic
    fun debug(source: Class<*>, method: String, msg: Any) {
        DARKAN_ROOT_LOGGER.log(Level.FINE, formatMessage(source.simpleName, method, msg))
    }

    @JvmStatic
    fun trace(source: Class<*>, method: String, msg: Any) {
        DARKAN_ROOT_LOGGER.log(Level.FINER, formatMessage(source.simpleName, method, msg))
    }

    @JvmStatic
    fun log(tag: String, message: Any) {
        DARKAN_ROOT_LOGGER.log(Level.INFO, "[$tag] $message")
    }
}