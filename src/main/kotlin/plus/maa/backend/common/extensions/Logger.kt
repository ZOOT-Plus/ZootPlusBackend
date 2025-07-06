package plus.maa.backend.common.extensions

import io.github.oshai.kotlinlogging.KLogger

class LoggerCtx(private val logger: KLogger, private val prefix: String) {
    fun logI(message: () -> String) = logger.info { "[$prefix] ${message()}" }
    fun logE(e: Exception, message: () -> String) = logger.error(e) { "[LEVEL]${message()}" }
}

suspend fun <T> KLogger.traceRun(prefix: String, block: suspend LoggerCtx.() -> T) = LoggerCtx(this, prefix).block()
