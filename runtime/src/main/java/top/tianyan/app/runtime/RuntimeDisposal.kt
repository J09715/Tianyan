package top.tianyan.app.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal suspend fun checkRuntimeDisposal(resource: Any) {
    check(resource !in currentCoroutineContext()[Disposing]?.resources.orEmpty()) {
        "Cannot recursively dispose the same runtime resource"
    }
}
internal suspend fun <T> withRuntimeDisposal(resource: Any, block: suspend () -> T): T {
    checkRuntimeDisposal(resource)
    val active = currentCoroutineContext()[Disposing]?.resources.orEmpty()
    return withContext(NonCancellable + Disposing(active + resource)) { block() }
}
private class Disposing(val resources: Set<Any>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Disposing>
}
