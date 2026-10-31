package top.tianyan.app.runtime.remote

import java.io.InputStream
import java.io.ByteArrayOutputStream

internal fun InputStream.readBounded(maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        output.write(buffer, 0, count)
        require(output.size() <= maximum) { "远程文件超过读取上限" }
    }
}
