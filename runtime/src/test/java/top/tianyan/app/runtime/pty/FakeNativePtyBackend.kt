package top.tianyan.app.runtime.pty

import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class FakeNativePtyBackend : NativePtyBackend {
    @Volatile var alive = true
    @Volatile var fdClosed = false
    var gracefulExit = true
    var forcedExit = true
    var signalFailures = 0
    var closeFailures = 0
    var reapFailures = 0
    var zeroWrites = 0
    var partialWrite = Int.MAX_VALUE
    val readEntered = CountDownLatch(1)
    val readBlocked = CountDownLatch(1)
    @Volatile var readGate = CountDownLatch(0)
    val writeEntered = CountDownLatch(1)
    @Volatile var writeGate = CountDownLatch(0)
    val signalled = CountDownLatch(1)
    val chunks = ConcurrentLinkedQueue<ByteArray>()
    val signals = Collections.synchronizedList(mutableListOf<Int>())
    val written = Collections.synchronizedList(mutableListOf<Byte>())
    val closes = AtomicInteger()
    val reaps = AtomicInteger()
    val reads = AtomicInteger()
    val resizes = AtomicInteger()
    override fun read(fd: Int, buffer: ByteArray): Int {
        readEntered.countDown()
        if (readGate.count > 0) readBlocked.countDown()
        check(readGate.await(3, TimeUnit.SECONDS))
        check(!fdClosed) { "read after descriptor reuse" }
        reads.incrementAndGet()
        val bytes = chunks.poll()
        if (bytes != null) { bytes.copyInto(buffer); return bytes.size }
        if (!alive) return -1
        Thread.sleep(5)
        return 0
    }
    override fun write(fd: Int, data: ByteArray, offset: Int, length: Int): Int {
        writeEntered.countDown()
        check(writeGate.await(3, TimeUnit.SECONDS))
        check(!fdClosed) { "write after descriptor reuse" }
        if (zeroWrites-- > 0) return 0
        val count = minOf(partialWrite, length)
        written.addAll(data.copyOfRange(offset, offset + count).toList())
        return count
    }
    override fun resize(fd: Int, columns: Int, rows: Int) {
        check(!fdClosed)
        resizes.incrementAndGet()
    }
    override fun signal(pid: Int, signal: Int) {
        signals += signal
        signalled.countDown()
        if (signalFailures-- > 0) throw IOException("signal failed")
        if ((signal == 1 && gracefulExit) || (signal == 9 && forcedExit)) alive = false
    }
    override fun pollExit(pid: Int): Boolean {
        reaps.incrementAndGet()
        if (reapFailures-- > 0) throw IOException("reap failed")
        return !alive
    }
    override fun close(fd: Int) {
        closes.incrementAndGet()
        if (closeFailures-- > 0) throw IOException("descriptor close failed")
        fdClosed = true
    }
}
