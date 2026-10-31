package top.tianyan.app.runtime.rootfs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TarHardlinkCopyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val recordedChmods = mutableListOf<Pair<String, Int>>()

    @Test fun `hardlink copy keeps the source executable mode`() = runBlocking {
        val destination = temporaryFolder.newFolder("rootfs")
        val fs = object : FileSystemOps {
            override fun symlink(target: String, linkPath: String) = error("unused")
            override fun chmod(path: String, mode: Int) { recordedChmods += path to mode }
            override fun hardlink(existing: String, link: String) = error("hardlink unsupported")
            override fun posixMode(path: String): Int? = recordedChmods.lastOrNull { it.first == path }?.second
        }
        val extractor = TarStreamExtractor({ _, _ -> }, fs)
        val payload = "#!/bin/busybox\n".toByteArray()
        val mode = 0b111101101
        extractor.extract(ByteArrayInputStream(tar(payload, mode)), destination, handleWhiteouts = false)
        assertEquals(String(payload), File(destination, "usr/bin/id").readText())
        assertEquals(String(payload), File(destination, "usr/bin/tr").readText())
        listOf("usr/bin/id", "usr/bin/tr").forEach { relative ->
            // chmod 收到的是宿主路径：Windows 分隔符是 `\`，断言前统一成 `/`。
            val applied = recordedChmods.last { it.first.normalizedPath().endsWith("/$relative") }.second
            assertEquals(relative, mode, applied and 0xfff)
        }
        assertTrue(recordedChmods.any { it.first.normalizedPath().endsWith("/usr/bin/busybox") })
    }

    private fun String.normalizedPath(): String = replace('\\', '/')

    private fun tar(payload: ByteArray, mode: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header("usr/bin/busybox", '0', payload.size.toLong(), mode, ""))
        out.write(payload)
        val padding = (512 - (payload.size % 512)) % 512
        if (padding > 0) out.write(ByteArray(padding))
        out.write(header("usr/bin/id", '1', 0, mode, "usr/bin/busybox"))
        out.write(header("usr/bin/tr", '1', 0, mode, "usr/bin/busybox"))
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun header(name: String, type: Char, size: Long, mode: Int, link: String): ByteArray {
        val bytes = ByteArray(512)
        write(bytes, 0, 100, name)
        writeOctal(bytes, 100, 8, mode.toLong())
        writeOctal(bytes, 124, 12, size)
        writeOctal(bytes, 136, 12, 1_700_000_000)
        bytes[156] = type.code.toByte()
        write(bytes, 157, 100, link)
        write(bytes, 257, 6, "ustar\u0000")
        write(bytes, 263, 2, "00")
        for (i in 148 until 156) bytes[i] = ' '.code.toByte()
        val sum = bytes.sumOf { it.toUByte().toLong() }
        val octal = "%06o".format(sum).toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(octal, 0, bytes, 148, 6)
        bytes[154] = 0
        bytes[155] = ' '.code.toByte()
        return bytes
    }

    private fun write(dest: ByteArray, offset: Int, length: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(bytes, 0, dest, offset, minOf(bytes.size, length))
    }

    private fun writeOctal(dest: ByteArray, offset: Int, length: Int, value: Long) {
        val octal = "%0${length - 1}o".format(value)
        write(dest, offset, length - 1, octal)
        dest[offset + length - 1] = 0
    }
}
