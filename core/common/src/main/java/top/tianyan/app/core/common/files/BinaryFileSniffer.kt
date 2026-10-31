package top.tianyan.app.core.common.files

/**
 * 判断一段字节是否「不像文本」：编辑器、文本预览与 `text/plain` 导出都不适合处理二进制。
 *
 * 规则刻意保守——只拦明显是二进制的内容（魔数、NUL、控制字符占比过高），
 * 宁可漏判也不误伤 Markdown / JSON / 中文 UTF-8 等正常文本。
 * 纯 Kotlin，无 Android 依赖，可单测。
 */
object BinaryFileSniffer {
    /** 只嗅探文件头部这么多个字节；再往后读对判断没有帮助却会拖慢大文件。 */
    const val SAMPLE_BYTES = 8 * 1024

    /** 控制字符（除 \t \n \r \f \b 这些文本里常见的）占比超过此百分比即视为二进制。 */
    private const val CONTROL_RATIO_PERCENT = 10

    /** 常见二进制格式的魔数（按前缀匹配）。命中即判定二进制，不看后续统计。 */
    private val MAGIC_PREFIXES: List<ByteArray> = listOf(
        // 图片
        bytes(0x89, 0x50, 0x4E, 0x47), // PNG
        bytes(0xFF, 0xD8, 0xFF), // JPEG
        bytes(0x47, 0x49, 0x46, 0x38), // GIF8
        // 压缩 / 打包（APK 同样是 zip）
        bytes(0x50, 0x4B, 0x03, 0x04), // ZIP (PK\3\4)
        bytes(0x50, 0x4B, 0x05, 0x06), // 空 ZIP
        bytes(0x50, 0x4B, 0x07, 0x08), // 跨卷 ZIP
        bytes(0x1F, 0x8B), // gzip
        bytes(0x42, 0x5A, 0x68), // bzip2
        bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00), // xz
        bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C), // 7z
        bytes(0x52, 0x61, 0x72, 0x21), // RAR
        // 可执行 / 运行时产物
        bytes(0x7F, 0x45, 0x4C, 0x46), // ELF (Linux .so / 可执行)
        bytes(0x4D, 0x5A), // PE / .exe
        bytes(0xCE, 0xFA, 0xED, 0xFE), // Mach-O 32
        bytes(0xCF, 0xFA, 0xED, 0xFE), // Mach-O 64
        bytes(0xCA, 0xFE, 0xBA, 0xBE), // Java class / Mach-O fat
        bytes(0x64, 0x65, 0x78, 0x0A), // dex
        bytes(0x00, 0x61, 0x73, 0x6D), // wasm
        // 文档 / 数据 / 媒体
        bytes(0x25, 0x50, 0x44, 0x46), // %PDF
        bytes(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66), // SQLite format 3
        bytes(0x4F, 0x67, 0x67, 0x53), // Ogg
        bytes(0x66, 0x4C, 0x61, 0x43), // FLAC
        bytes(0x52, 0x49, 0x46, 0x46), // RIFF (wav/avi/webp)
        bytes(0x1A, 0x45, 0xDF, 0xA3), // Matroska / webm
        bytes(0x77, 0x4F, 0x46, 0x46), // wOFF
        bytes(0x4F, 0x54, 0x54, 0x4F), // OTF
    )

    private val UTF8_BOM = bytes(0xEF, 0xBB, 0xBF)
    private val UTF16_BOMS = listOf(bytes(0xFF, 0xFE), bytes(0xFE, 0xFF))

    /**
     * @param bytes 头部字节（通常取 [SAMPLE_BYTES]）
     * @param length 实际有效长度，允许传入超出头部大小的数组
     */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val size = length.coerceIn(0, bytes.size)
        if (size == 0) return false
        if (startsWith(bytes, size, UTF8_BOM)) return false
        if (UTF16_BOMS.any { startsWith(bytes, size, it) }) return true
        if (MAGIC_PREFIXES.any { startsWith(bytes, size, it) }) return true
        var control = 0
        for (index in 0 until size) {
            val value = bytes[index].toInt() and 0xFF
            when {
                value == 0x00 -> return true
                value == 0x09 || value == 0x0A || value == 0x0C || value == 0x0D || value == 0x08 -> Unit
                value < 0x20 || value == 0x7F -> control++
            }
        }
        return control * 100 > size * CONTROL_RATIO_PERCENT
    }

    private fun startsWith(bytes: ByteArray, length: Int, prefix: ByteArray): Boolean {
        if (prefix.size > length) return false
        for (index in prefix.indices) if (bytes[index] != prefix[index]) return false
        return true
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}