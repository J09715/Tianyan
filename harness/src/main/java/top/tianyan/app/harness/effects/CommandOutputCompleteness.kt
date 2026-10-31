package top.tianyan.app.harness.effects

/** Runtime capture limits must not be relabelled as a complete output by the final spill hint. */
internal fun hasIncompleteCommandOutput(output: String): Boolean = listOf(
    "[输出保存达到上限：", "[完整输出缓存", "[输出管道未完全排空，", "[进程输出已截断：",
).any(output::contains)
