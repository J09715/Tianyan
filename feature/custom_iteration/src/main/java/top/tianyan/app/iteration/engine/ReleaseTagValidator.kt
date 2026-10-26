package top.tianyan.app.iteration.engine

/**
 * tianyandev-build.yml 的 workflow_dispatch 输入 release_tag 校验器。
 *
 * 该值会被工作流传给 softprops/action-gh-release 的 tag_name/name 字段。
 * 这里只接受保守形状 `v<主>.<次>.<补丁>[-后缀]`（后缀段允许字母/数字/点/连字符）。
 * 下划线故意拒绝，例如 `v1.0.0_rc1`。工作流 bash 步骤使用同一形状，并在创建 Release 之前执行。
 *
 * 空输入视为「不创建 Release」（对应工作流输入的默认空值），返回有效。
 */
internal object ReleaseTagValidator {

    private val PATTERN = Regex("""^v\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$""")

    /**
     * @return 合法（含空输入 = 不创建 Release）返回 null；否则返回可直接展示给用户的拒绝原因。
     */
    fun validate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val tag = raw.trim()
        if (PATTERN.matches(tag)) return null
        return "无效的 release tag \"$tag\"：应为 v<主>.<次>.<补丁>[-后缀]，如 v0.2.1-dev"
    }
}
