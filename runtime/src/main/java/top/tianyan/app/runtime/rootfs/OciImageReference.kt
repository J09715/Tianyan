package top.tianyan.app.runtime.rootfs

/** Docker reference grammar; Tianyan's installation routes currently serve Docker Hub images. */
internal data class OciImageReference(val repo: String, val tag: String, val digest: String?) {
    val manifestRef: String get() = digest ?: tag

    companion object {
        private val component = "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*"
        private val grammar = Regex("($component(?:/$component)*)(?::([A-Za-z0-9_][A-Za-z0-9_.-]{0,127}))?(?:@(sha256:[a-f0-9]{64}))?")

        fun parse(value: String): OciImageReference {
            require(value.isNotEmpty() && value.length <= 1024) { "镜像引用为空或过长" }
            require(!value.matches(Regex("[a-f0-9]{64}"))) { "镜像 ID 不能用作镜像名称" }
            val first = value.substringBefore('/')
            val explicitHost = '/' in value && ('.' in first || ':' in first || first == "localhost" || first != first.lowercase())
            require(!explicitHost || first in setOf("docker.io", "index.docker.io")) {
                "当前安装线路只支持 Docker Hub 镜像"
            }
            val name = if (explicitHost) value.substringAfter('/') else value
            val match = grammar.matchEntire(name)
            require(match != null) { "镜像引用格式无效：仓库须小写，tag 或 SHA256 digest 格式错误" }
            val path = match.groupValues[1]
            val repo = if ('/' in path) path else "library/$path"
            require("docker.io/$repo".length <= 255) { "镜像仓库名称过长" }
            val digest = match.groupValues[3].ifEmpty { null }
            return OciImageReference(repo, match.groupValues[2].ifEmpty { if (digest == null) "latest" else "" }, digest)
        }
    }
}
