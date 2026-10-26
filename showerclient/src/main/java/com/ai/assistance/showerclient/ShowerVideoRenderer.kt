package com.ai.assistance.showerclient

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * H.264 decoder that renders the Shower video stream onto a Surface.
 * Each instance handles one video stream for a specific virtual display.
 */
class ShowerVideoRenderer {

    companion object {
        private const val TAG = "ShowerVideoRenderer"

        /**
         * 等待 SPS/PPS 期间最多缓存的包数（约 1–2s 码率）。解码器未初始化的等待路径
         * 没有输入缓冲那样的天然背压：中途接入流 / 长 GOP 场景下参数集迟迟不到，
         * 视频包会以每秒数百 KB 的速度无限堆积，直到拖爆 Java 堆（历史 OOM 闪退根因）。
         */
        internal const val MAX_PENDING_FRAMES = 60

        /** 活实例弱引用表：供 [trimAllPending] 遍历；弱引用避免延长 SurfaceView 生命周期。 */
        private val activeRenderers = CopyOnWriteArrayList<WeakReference<ShowerVideoRenderer>>()

        /** 内存水位哨兵入口：丢弃所有活实例的等待缓冲（可再生数据，画面随下一个 IDR 恢复）。 */
        @JvmStatic
        fun trimAllPending() {
            activeRenderers.removeAll { it.get() == null }
            activeRenderers.forEach { it.get()?.trimPendingFrames() }
        }
    }

    private val lock = Any()

    init {
        activeRenderers.removeAll { it.get() == null }
        activeRenderers.add(WeakReference(this))
    }

    @Volatile
    private var decoder: MediaCodec? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var csd0: ByteArray? = null

    @Volatile
    private var csd1: ByteArray? = null

    private val pendingFrames = mutableListOf<ByteArray>()

    @Volatile
    private var width: Int = 0

    @Volatile
    private var height: Int = 0

    @Volatile
    private var warnedNoSurface: Boolean = false

    fun attach(surface: Surface, videoWidth: Int, videoHeight: Int) {
        synchronized(lock) {
            this.surface = surface
            this.width = videoWidth
            this.height = videoHeight
            warnedNoSurface = false
            releaseDecoderLocked()
            pendingFrames.clear()
        }
    }

    fun detach() {
        synchronized(lock) {
            releaseDecoderLocked()
            surface = null
            pendingFrames.clear()
            warnedNoSurface = false
        }
    }

    private fun releaseDecoderLocked() {
        val dec = decoder
        decoder = null
        if (dec != null) {
            try {
                dec.stop()
            } catch (_: Exception) {
            }
            try {
                dec.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 丢弃等待 SPS/PPS 的缓冲帧（内存水位哨兵联动）：属可再生数据，
     * 画面会在下一个 IDR + 参数集到达后自动恢复。
     */
    fun trimPendingFrames() {
        synchronized(lock) { pendingFrames.clear() }
    }

    /** Called for each H.264 packet. */
    fun onFrame(data: ByteArray) {
        synchronized(lock) {
            if (surface == null || width <= 0 || height <= 0) {
                if (!warnedNoSurface) {
                    ShowerLog.w(TAG, "onFrame: no surface or invalid size; dropping frames")
                    warnedNoSurface = true
                }
                return
            }

            // server 把 MediaFormat 的 csd-0/csd-1 原样回传。有的机型没有 Annex-B 起始码，
            // 有的把 SPS 和 PPS 粘在同一个包里。认不出这两类参数集时解码器永远不初始化，
            // 悬浮窗就是一块黑的。
            for (nal in splitAnnexB(ensureAnnexB(data))) {
                if (decoder == null) {
                    val nalType = findNalUnitType(nal)
                    if (nalType == 7) { // SPS
                        if (csd0 == null) csd0 = nal
                    } else if (nalType == 8) { // PPS
                        if (csd1 == null) csd1 = nal
                    } else {
                        // 与 queueFrameToDecoder 的丢包背压对齐：等待参数集期间最多缓存
                        // [MAX_PENDING_FRAMES] 包，超限丢最旧，防止无限堆积拖爆 Java 堆。
                        while (pendingFrames.size >= MAX_PENDING_FRAMES) pendingFrames.removeAt(0)
                        pendingFrames.add(nal)
                    }

                    if (csd0 != null && csd1 != null) {
                        initDecoderLocked()
                        val framesToProcess = pendingFrames.toList()
                        pendingFrames.clear()
                        framesToProcess.forEach { frame -> queueFrameToDecoder(frame) }
                    }
                } else {
                    queueFrameToDecoder(nal)
                }
            }
        }
    }

    private fun findNalUnitType(packet: ByteArray): Int {
        var offset = -1
        for (i in 0 until packet.size - 3) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte()) {
                if (packet[i + 2] == 1.toByte()) {
                    offset = i + 3
                    break
                } else if (i + 3 < packet.size && packet[i + 2] == 0.toByte() && packet[i + 3] == 1.toByte()) {
                    offset = i + 4
                    break
                }
            }
        }
        if (offset != -1 && offset < packet.size) {
            return (packet[offset].toInt() and 0x1F)
        }
        return -1
    }

    private fun queueFrameToDecoder(packet: ByteArray) {
        synchronized(lock) {
            val dec = decoder ?: return
            try {
                val inIndex = dec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer: ByteBuffer? = dec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(packet)
                        dec.queueInputBuffer(inIndex, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                }

                val bufferInfo = BufferInfo()
                var outIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    dec.releaseOutputBuffer(outIndex, true)
                    outIndex = dec.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (e: Exception) {
                ShowerLog.e(TAG, "Decoder error on frame", e)
                releaseDecoderLocked()
                pendingFrames.clear()
            }
        }
    }

    /** 按 Annex-B 起始码切开。没有起始码时整包当作一个 NAL。 */
    private fun splitAnnexB(packet: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>(4)
        var i = 0
        while (i < packet.size - 3) {
            if (packet[i] == 0.toByte() && packet[i + 1] == 0.toByte()) {
                if (packet[i + 2] == 1.toByte()) {
                    starts += i
                    i += 3
                    continue
                }
                if (i + 3 < packet.size && packet[i + 2] == 0.toByte() && packet[i + 3] == 1.toByte()) {
                    starts += i
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.size <= 1) return listOf(packet)
        return starts.indices.map { index ->
            val end = if (index + 1 < starts.size) starts[index + 1] else packet.size
            packet.copyOfRange(starts[index], end)
        }
    }

    /**
     * 统一成 Annex-B。已经带起始码的包原样返回；AVCC 长度前缀转成起始码；
     * 剩下的裸 NAL（csd-0 常见以 0x67 开头）补上 00 00 00 01。
     */
    private fun ensureAnnexB(packet: ByteArray): ByteArray {
        if (hasStartCode(packet)) return packet
        val avcc = maybeAvccToAnnexb(packet)
        if (avcc !== packet && hasStartCode(avcc)) return avcc
        val nalType = packet.firstOrNull()?.toInt()?.and(0x1F) ?: return packet
        if (nalType !in 1..23) return packet
        val out = ByteArray(packet.size + 4)
        out[3] = 1
        System.arraycopy(packet, 0, out, 4, packet.size)
        return out
    }

    private fun hasStartCode(packet: ByteArray): Boolean {
        if (packet.size < 4) return false
        val b0 = packet[0].toInt() and 0xFF
        val b1 = packet[1].toInt() and 0xFF
        val b2 = packet[2].toInt() and 0xFF
        val b3 = packet[3].toInt() and 0xFF
        return b0 == 0 && b1 == 0 && ((b2 == 0 && b3 == 1) || b2 == 1)
    }

    private fun maybeAvccToAnnexb(packet: ByteArray): ByteArray {
        if (packet.size >= 4) {
            val b0 = packet[0].toInt() and 0xFF
            val b1 = packet[1].toInt() and 0xFF
            val b2 = packet[2].toInt() and 0xFF
            val b3 = packet[3].toInt() and 0xFF
            if (b0 == 0 && b1 == 0 && ((b2 == 0 && b3 == 1) || b2 == 1)) {
                return packet
            }
        }
        val out = ByteArrayOutputStream()
        var i = 0
        val n = packet.size
        while (i + 4 <= n) {
            val nalLen =
                ((packet[i].toInt() and 0xFF) shl 24) or
                    ((packet[i + 1].toInt() and 0xFF) shl 16) or
                    ((packet[i + 2].toInt() and 0xFF) shl 8) or
                    (packet[i + 3].toInt() and 0xFF)
            i += 4
            if (nalLen <= 0 || i + nalLen > n) return packet
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(packet, i, nalLen)
            i += nalLen
        }
        val result = out.toByteArray()
        return if (result.isNotEmpty()) result else packet
    }

    suspend fun captureCurrentFramePng(): ByteArray? {
        val s: Surface
        val w: Int
        val h: Int
        synchronized(lock) {
            val localSurface = surface
            if (localSurface == null || width <= 0 || height <= 0) return null
            s = localSurface
            w = width
            h = height
        }

        if (Build.VERSION.SDK_INT < 26) return null

        return withContext(Dispatchers.Main) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                PixelCopy.request(s, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        try {
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                            cont.resume(baos.toByteArray())
                        } catch (e: Exception) {
                            cont.resume(null)
                        } finally {
                            bitmap.recycle()
                        }
                    } else {
                        bitmap.recycle()
                        cont.resume(null)
                    }
                }, handler)
            }
        }
    }

    private fun initDecoderLocked() {
        val s = surface ?: return
        val localCsd0 = csd0 ?: return
        val localCsd1 = csd1 ?: return
        if (width <= 0 || height <= 0) return

        try {
            val csd0Annexb = maybeAvccToAnnexb(localCsd0)
            val csd1Annexb = maybeAvccToAnnexb(localCsd1)

            val format = MediaFormat.createVideoFormat("video/avc", width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Annexb))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1Annexb))

            val dec = MediaCodec.createDecoderByType("video/avc")
            dec.configure(format, s, null, 0)
            dec.start()
            decoder = dec
            ShowerLog.d(TAG, "MediaCodec decoder initialized for ${width}x${height}")
        } catch (e: Exception) {
            ShowerLog.e(TAG, "Failed to init decoder", e)
            releaseDecoderLocked()
        }
    }
}
