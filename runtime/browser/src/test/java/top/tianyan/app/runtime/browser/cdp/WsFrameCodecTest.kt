package top.tianyan.app.runtime.browser.cdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** RFC 6455 帧编解码纯函数测试：掩码、扩展长度、分片、ping/pong、握手。 */
class WsFrameCodecTest {

    @Test
    fun `client text frame roundtrip via decoder`() {
        val payload = """{"id":1,"method":"Debugger.enable"}"""
        val encoded = WsFrameCodec.encodeText(payload)
        // 客户端帧必须置掩码位
        assertTrue("mask bit must be set", (encoded[1].toInt() and 0x80) != 0)
        val frames = WsFrameDecoder().feed(encoded)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertTrue(frame.fin)
        assertEquals(WsFrameCodec.OP_TEXT, frame.opcode)
        assertEquals(payload, String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun `16-bit extended length`() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_BINARY, payload)
        assertEquals(126, (encoded[1].toInt() and 0x7F))
        val frames = WsFrameDecoder().feed(encoded)
        assertArrayEquals(payload, frames.single().payload)
    }

    @Test
    fun `64-bit extended length`() {
        val payload = ByteArray(70_000) { (it % 253).toByte() }
        val encoded = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_BINARY, payload)
        assertEquals(127, (encoded[1].toInt() and 0x7F))
        val frames = WsFrameDecoder().feed(encoded)
        assertArrayEquals(payload, frames.single().payload)
    }

    @Test
    fun `byte-at-a-time feed reassembles frame`() {
        val payload = "split-across-reads"
        val encoded = WsFrameCodec.encodeText(payload)
        val decoder = WsFrameDecoder()
        val frames = ArrayList<WsFrameCodec.Frame>()
        // 逐字节喂（模拟最极端的分包）
        encoded.forEach { b -> frames += decoder.feed(byteArrayOf(b)) }
        // 全部喂完后应恰好有一个完整帧
        val complete = frames.filter { it.opcode == WsFrameCodec.OP_TEXT }
        // 逐字节喂时只有最后一个字节才让帧完整
        assertEquals(1, complete.size)
        assertEquals(payload, String(complete.single().payload, Charsets.UTF_8))
    }

    @Test
    fun `multiple frames in single feed`() {
        val a = WsFrameCodec.encodeText("one")
        val b = WsFrameCodec.encodeText("two")
        val c = WsFrameCodec.encodeClose(1000, "bye")
        val merged = ByteArrayOutputStream().apply { write(a); write(b); write(c) }.toByteArray()
        val frames = WsFrameDecoder().feed(merged)
        assertEquals(3, frames.size)
        assertEquals("one", String(frames[0].payload, Charsets.UTF_8))
        assertEquals("two", String(frames[1].payload, Charsets.UTF_8))
        assertEquals(WsFrameCodec.OP_CLOSE, frames[2].opcode)
    }

    @Test
    fun `unmasked server frame decodes`() {
        // 手工构造服务端帧（无掩码）：文本 "hi"
        val payload = "hi".toByteArray(Charsets.UTF_8)
        val frame = byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload
        val frames = WsFrameDecoder().feed(frame)
        assertEquals(1, frames.size)
        assertEquals("hi", String(frames.single().payload, Charsets.UTF_8))
    }

    @Test
    fun `ping pong roundtrip`() {
        val ping = WsFrameCodec.encodeClientFrame(WsFrameCodec.OP_PING, "hb".toByteArray())
        val pong = WsFrameCodec.encodePong("hb".toByteArray())
        val decodedPing = WsFrameDecoder().feed(ping)
        val decodedPong = WsFrameDecoder().feed(pong)
        assertEquals(WsFrameCodec.OP_PING, decodedPing.single().opcode)
        assertEquals(WsFrameCodec.OP_PONG, decodedPong.single().opcode)
        assertArrayEquals(decodedPing.single().payload, decodedPong.single().payload)
    }

    @Test
    fun `handshake request and accept validation`() {
        val key = WsFrameCodec.newWebSocketKey()
        val req = WsFrameCodec.handshakeRequest("/devtools/page/ABC", "127.0.0.1", key)
        assertTrue(req.contains("GET /devtools/page/ABC HTTP/1.1"))
        assertTrue(req.contains("Sec-WebSocket-Key: $key"))
        assertTrue(req.contains("Sec-WebSocket-Version: 13"))

        val accept = WsFrameCodec.expectedAccept(key)
        val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n" +
            "\r\n"
        assertTrue(WsFrameCodec.validateHandshake(resp, key))
        // 错误的 accept 必须拒绝
        assertFalse(WsFrameCodec.validateHandshake(resp, "wrong-key"))
        // 非 101 拒绝
        val not101 = "HTTP/1.1 400 Bad Request\r\n\r\n"
        assertFalse(WsFrameCodec.validateHandshake(not101, key))
    }

    @Test
    fun `header extraction case insensitive`() {
        val headers = "HTTP/1.1 101 Switching Protocols\r\nsec-websocket-accept: abc==\r\n\r\n"
        assertEquals("abc==", WsFrameCodec.headerValue(headers, "Sec-WebSocket-Accept"))
        assertNull(WsFrameCodec.headerValue(headers, "Missing"))
    }

    @Test
    fun `invalid feed ranges are rejected`() {
        val decoder = WsFrameDecoder()
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 2 to 1)) {
            try {
                decoder.feed(byteArrayOf(1), offset, length)
                fail("range $offset/$length must be rejected")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `reserved opcode and rsv bits are rejected`() {
        for (frame in listOf(byteArrayOf(0x83.toByte(), 0), byteArrayOf(0xC1.toByte(), 0))) {
            try {
                WsFrameDecoder().feed(frame)
                fail("invalid frame must be rejected")
            } catch (_: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun `control frames must be final and at most 125 bytes`() {
        val oversizedPing = byteArrayOf(0x89.toByte(), 126, 0, 126) + ByteArray(126)
        val fragmentedPing = byteArrayOf(0x09, 0)
        for (frame in listOf(oversizedPing, fragmentedPing)) {
            try {
                WsFrameDecoder().feed(frame)
                fail("invalid control frame must be rejected")
            } catch (_: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun `non-canonical extended lengths are rejected`() {
        val short16 = byteArrayOf(0x82.toByte(), 126, 0, 1, 1)
        val short64 = byteArrayOf(0x82.toByte(), 127, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte())
        for (frame in listOf(short16, short64)) {
            try {
                WsFrameDecoder().feed(frame)
                fail("non-canonical payload length must be rejected")
            } catch (_: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun `close frame rejects one-byte payload`() {
        try {
            WsFrameDecoder().feed(byteArrayOf(0x88.toByte(), 1, 0))
            fail("one-byte close payload must be rejected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `reserved high bit in 64 bit payload length is rejected`() {
        val frame = byteArrayOf(0x82.toByte(), 127, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)
        try {
            WsFrameDecoder().feed(frame)
            fail("reserved payload length bit must be rejected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `fragmented messages accept continuation frames and reset after final`() {
        val decoder = WsFrameDecoder()
        val first = decoder.feed(byteArrayOf(0x01, 3, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte()))
        val middle = decoder.feed(byteArrayOf(0x00, 1, 'l'.code.toByte()))
        val last = decoder.feed(byteArrayOf(0x80.toByte(), 1, 'o'.code.toByte()))
        assertEquals(WsFrameCodec.OP_TEXT, first.single().opcode)
        assertFalse(first.single().fin)
        assertEquals(WsFrameCodec.OP_CONTINUATION, middle.single().opcode)
        assertFalse(middle.single().fin)
        assertEquals(WsFrameCodec.OP_CONTINUATION, last.single().opcode)
        assertTrue(last.single().fin)
        assertEquals("hello", (first + middle + last).filter { it.opcode == WsFrameCodec.OP_CONTINUATION }
            .flatMap { it.payload.toList() }.toByteArray().let {
                String(first.single().payload + it, Charsets.UTF_8)
            })

        val standalone = decoder.feed(byteArrayOf(0x81.toByte(), 1, 'x'.code.toByte()))
        assertEquals("x", String(standalone.single().payload, Charsets.UTF_8))
    }

    @Test
    fun `continuation without a fragmented message is rejected`() {
        try {
            WsFrameDecoder().feed(byteArrayOf(0x80.toByte(), 0))
            fail("unexpected continuation must be rejected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `new data frame cannot interrupt a fragmented message`() {
        val decoder = WsFrameDecoder()
        decoder.feed(byteArrayOf(0x01, 1, 'a'.code.toByte()))
        try {
            decoder.feed(byteArrayOf(0x81.toByte(), 1, 'b'.code.toByte()))
            fail("interleaved data message must be rejected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `fragmented message cumulative size is limited`() {
        val decoder = WsFrameDecoder(maxFrameBytes = 3)
        decoder.feed(byteArrayOf(0x01, 2, 'a'.code.toByte(), 'b'.code.toByte()))
        try {
            decoder.feed(byteArrayOf(0x80.toByte(), 2, 'c'.code.toByte(), 'd'.code.toByte()))
            fail("oversized fragmented message must be rejected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `frame limit is capped to keep buffer arithmetic bounded`() {
        try {
            WsFrameDecoder(maxFrameBytes = Int.MAX_VALUE)
            fail("oversized configured limit must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `configured frame limit is enforced before payload buffering`() {
        val decoder = WsFrameDecoder(maxFrameBytes = 3)
        try {
            decoder.feed(byteArrayOf(0x82.toByte(), 4, 1, 2, 3, 4))
            fail("frame larger than limit must be rejected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `close frame encodes status code`() {
        val encoded = WsFrameCodec.encodeClose(1000, "detach")
        val frames = WsFrameDecoder().feed(encoded)
        val f = frames.single()
        assertEquals(WsFrameCodec.OP_CLOSE, f.opcode)
        val code = ((f.payload[0].toInt() and 0xFF) shl 8) or (f.payload[1].toInt() and 0xFF)
        assertEquals(1000, code)
        assertEquals("detach", String(f.payload, 2, f.payload.size - 2, Charsets.UTF_8))
    }
}
