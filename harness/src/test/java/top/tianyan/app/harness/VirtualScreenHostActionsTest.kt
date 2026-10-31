package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualScreenHostActionsTest {
    @Test
    fun `png dimensions come from the IHDR header`() {
        val png = ByteArray(24)
        png[0] = 0x89.toByte()
        png[1] = 'P'.code.toByte()
        png[2] = 'N'.code.toByte()
        png[3] = 'G'.code.toByte()
        // width 1216, height 2640, big-endian at offsets 16 and 20
        png[16] = 0
        png[17] = 0
        png[18] = 4
        png[19] = 192.toByte()
        png[20] = 0
        png[21] = 0
        png[22] = 10
        png[23] = 80
        assertEquals(1216 to 2640, VirtualScreenHostActions.pngDimensions(png))
        assertEquals(0 to 0, VirtualScreenHostActions.pngDimensions(byteArrayOf(1, 2, 3)))
    }
}
