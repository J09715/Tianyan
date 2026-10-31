package top.tianyan.app.core.common.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryFileSnifferTest {

    @Test
    fun `plain text formats are not binary`() {
        assertFalse(BinaryFileSniffer.looksBinary("plain ascii".toByteArray()))
        assertFalse(BinaryFileSniffer.looksBinary("# 标题\n\n- 列表项 `code`\n".toByteArray()))
        assertFalse(BinaryFileSniffer.looksBinary("""{"model":"gpt","temperature":0.7}""".toByteArray()))
        assertFalse(BinaryFileSniffer.looksBinary("中文说明：天衍运行时\n第二行\t带制表符\r\n".toByteArray()))
        assertFalse(BinaryFileSniffer.looksBinary(ByteArray(0)))
    }

    @Test
    fun `utf8 bom keeps the file textual`() {
        val text = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()
        assertFalse(BinaryFileSniffer.looksBinary(text))
    }

    @Test
    fun `known binary magic numbers are detected`() {
        assertTrue(BinaryFileSniffer.looksBinary(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertTrue(BinaryFileSniffer.looksBinary(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))) // zip / apk
        assertTrue(BinaryFileSniffer.looksBinary(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01))) // elf
        assertTrue(BinaryFileSniffer.looksBinary(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00))) // gzip
        assertTrue(BinaryFileSniffer.looksBinary("%PDF-1.7\n".toByteArray()))
        assertTrue(BinaryFileSniffer.looksBinary(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `embedded nul bytes mean binary even without magic`() {
        assertTrue(BinaryFileSniffer.looksBinary(byteArrayOf(0x61, 0x00, 0x62)))
    }

    @Test
    fun `dense control characters mean binary`() {
        val noisy = ByteArray(64) { if (it % 2 == 0) 0x01 else 0x41 }
        assertTrue(BinaryFileSniffer.looksBinary(noisy))
    }

    @Test
    fun `length parameter bounds the scan`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        // 只扫前 2 字节时魔数还没凑齐，且这两个字节本身不是控制字符
        assertFalse(BinaryFileSniffer.looksBinary(png, length = 2))
        assertTrue(BinaryFileSniffer.looksBinary(png, length = 4))
        assertTrue(BinaryFileSniffer.looksBinary(png))
    }
}