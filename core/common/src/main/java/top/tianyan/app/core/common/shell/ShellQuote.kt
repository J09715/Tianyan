package top.tianyan.app.core.common.shell

/**
 * POSIX 单引号转义的唯一实现。
 *
 * 全仓曾有 16 处各自私有的 shellQuote 变体（`'\''` 与 `'"'"'` 两种写法），语义等价
 * 但风格不一、容易继续漂移。这里收敛为单点。
 *
 * 规则：整体用单引号包裹；值内的每个 `'` 断开当前单引号 → 转义出一个 `'` → 续接新的
 * 单引号，即替换为 `'\''`。在 POSIX sh / bash / dash 下语义完全一致。
 */
object ShellQuote {

    /** 将 [value] 转义为可安全嵌入 POSIX shell 命令的单引号字面量。 */
    fun of(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}