package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.runtime.environment.ExecutionFiles
import top.tianyan.app.runtime.environment.ExecutionArtifacts

/** Verification stays in the selected execution world; never reads a host-side substitute. */
internal object ArtifactReadVerifier {
    suspend fun read(files: ExecutionFiles, path: String, args: JsonObject,
        perform: suspend () -> WorkspaceToolOutcome): WorkspaceToolOutcome {
        val expected = JsonArgs.requireString(args, "expected_sha256")
        require(expected.matches(Regex("[a-f0-9]{64}"))) { "expected_sha256 必须为 64 位小写十六进制" }
        require("query" !in args) { "产物完整性核验不能与 query 检索混用" }
        val before = files.fingerprint(path, ExecutionArtifacts.MAX_ARTIFACT_BYTES.toLong())
        if (before !is AppResult.Success) return unavailable()
        if (before.data.sha256 != expected) return changed()
        val outcome = perform()
        if (!outcome.success) return unavailable()
        val after = files.fingerprint(path, ExecutionArtifacts.MAX_ARTIFACT_BYTES.toLong())
        if (after !is AppResult.Success) return unavailable()
        if (after.data != before.data) return changed()
        return outcome.copy(output = outcome.output + "\n[artifact_status=VERIFIED：本次读取前后指纹匹配；不代表原始输出完整，也不保证未来仍可用]")
    }

    private fun unavailable() = WorkspaceToolOutcome(false,
        "[artifact_status=UNAVAILABLE] 引用文件已清理、不可访问、超出读取限制或当前环境不支持核验。大文本产物请提供 offset/limit 分页；历史引用不保证文件仍存在。请报告缺失，或在用户授权范围内重新获取来源，禁止自动重放旧操作。")
    private fun changed() = WorkspaceToolOutcome(false,
        "[artifact_status=CHANGED] 当前文件与历史版本不一致，本次不返回内容。不能把当前文件当作历史证据；请报告版本失效。")
}
