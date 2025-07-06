package plus.maa.backend.common.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicReference

inline fun <T> T?.requireNotNull(lazyMessage: () -> Any): T = requireNotNull(this, lazyMessage)

fun <T> lazySuspend(block: suspend () -> T): suspend () -> T {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val deferredRef = AtomicReference<Deferred<T>?>(null)
    return { deferredRef.updateAndGet { it ?: scope.async { block() } }!!.await() }
}

fun meetAll(vararg cond: Boolean) = cond.all { it }
