package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/** Durable execution identity; authentication material stays in the encrypted credential store. */
@Serializable
data class SessionExecutionTarget(val distribution: String, val workspace: String, val remote: RemoteServer? = null) {
    fun validate() {
        require(distribution.isNotBlank()) { "会话执行环境无效" }
        require((remote != null) == distribution.startsWith("ssh:")) { "会话执行环境类型不一致" }
        remote?.let { it.validate(); require(it.distributionId == distribution) }
    }
}
