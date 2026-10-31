package top.tianyan.app.runtime.shell

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No host process is launched; latches exercise an actual blocking reader thread. */
internal class FakeSessionProcess : Process() {
    @Volatile var alive = true
    var gracefulExit = true
    var forcedExit = true
    var waitFailure: Throwable? = null
    var beforeWait: () -> Unit = {}
    val destroys = AtomicInteger()
    val forces = AtomicInteger()
    val waits = AtomicInteger()
    val stdin = TrackedOutput()
    val stdout = BlockingInput()
    val stderr = TrackedInput()

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun isAlive() = alive
    override fun exitValue(): Int {
        check(!alive)
        return 0
    }
    override fun waitFor(): Int {
        stdout.eof.await()
        return exitValue()
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waits.incrementAndGet()
        beforeWait()
        waitFailure?.let { waitFailure = null; throw it }
        return !alive
    }
    override fun destroy() {
        destroys.incrementAndGet()
        if (gracefulExit) exit()
    }
    override fun destroyForcibly(): Process {
        forces.incrementAndGet()
        if (forcedExit) exit()
        return this
    }
    fun exit() { alive = false; stdout.eof.countDown() }

    class TrackedOutput : OutputStream() {
        val closes = AtomicInteger()
        val writes = AtomicInteger()
        var closeFailures = 0
        override fun write(b: Int) { writes.incrementAndGet() }
        override fun close() {
            closes.incrementAndGet()
            if (closeFailures > 0) { closeFailures--; throw IOException("stdin close failed") }
        }
    }

    class TrackedInput : ByteArrayInputStream(byteArrayOf()) {
        val closes = AtomicInteger()
        var closeFailures = 0
        override fun close() {
            closes.incrementAndGet()
            if (closeFailures > 0) { closeFailures--; throw IOException("stderr close failed") }
        }
    }

    class BlockingInput : InputStream() {
        val entered = CountDownLatch(1)
        val eof = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closes = AtomicInteger()
        var closeGate = CountDownLatch(0)
        override fun read(): Int {
            entered.countDown()
            check(eof.await(5, TimeUnit.SECONDS)) { "Fake process reader was not released" }
            return -1
        }
        override fun close() {
            closes.incrementAndGet()
            closeEntered.countDown()
            check(closeGate.await(5, TimeUnit.SECONDS)) { "Fake reader cleanup was not released" }
        }
    }
}
