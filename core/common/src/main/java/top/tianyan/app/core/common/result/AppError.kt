package top.tianyan.app.core.common.result

data class AppError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null,
)

enum class ErrorCode {
    UNKNOWN,
    IO,
    NETWORK,
    DOWNLOAD,
    CHECKSUM,
    RUNTIME_NOT_INITIALIZED,
    UNSUPPORTED_ARCHITECTURE,
    INSUFFICIENT_STORAGE,
    INSTALLATION_FAILED,
    DATABASE,
    SECURITY,
    /** 写入前的版本校验失败：目标已被外部修改（如编辑器草稿与磁盘版本不一致）。 */
    CONFLICT,
}