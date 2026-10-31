package top.tianyan.app.harness.groupchat

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Host-owned correlation, captured at admission before the native run is launched. */
internal class GroupWorkContext(val workId: String, val groupId: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GroupWorkContext>
}
