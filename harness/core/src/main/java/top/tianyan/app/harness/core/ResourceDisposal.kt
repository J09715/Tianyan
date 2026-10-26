package top.tianyan.app.harness.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Reject recursive release before acquiring a disposal lock, including across nested owners. */
suspend fun <T> withResourceDisposal(resource: Any, block: suspend () -> T): T {
    val active = currentCoroutineContext()[DisposingResources]?.resources.orEmpty()
    check(resource !in active) { "Cannot recursively dispose the same resource" }
    return withContext(NonCancellable + DisposingResources(active + resource)) { block() }
}

private class DisposingResources(val resources: Set<Any>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DisposingResources>
}
