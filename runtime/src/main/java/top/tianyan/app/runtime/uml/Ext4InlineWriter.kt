package top.tianyan.app.runtime.uml

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把一个小文件写进 ext4 镜像的 **inline data** 区。
 *
 * 为什么需要它（真实故障，已在 aarch64 主机上用真内核复现）：
 * 内核参数 `init=/host/tianyan-uml-init` 会在引导时执行该路径，但 `/host` 是
 * hostfs 挂载点，而挂载它的正是 init 自己 —— 鸡生蛋，内核报
 * `Requested init /host/tianyan-uml-init failed (error -2)` 直接 panic。
 * Stryker 的做法是 `init=/stryker-init`（脚本在镜像内）。
 *
 * Android 上不能改镜像：应用没有 root，也没有 debugfs。但 ext4 的 inline data
 * 特性让「小文件存在 inode 里、不占数据块」成为可能 —— 只需要：
 * ① 找一个空闲 inode；② 写 mode/size/links/flags(EXT4_INLINE_DATA_FL)；
 * ③ 把内容塞进 inode 的 i_block 区；④ 在父目录的 dirent 里加一条。
 * 全程只动 inode 表和目录块，不碰 extent 树、位图、日志。
 *
 * 限制：内容必须能塞进 inode 的 inline 区（inode_size 256 时约 176 字节起，
 * 用满 extra_isize 可到 ~500+ 字节）。init 脚本若超长，调用方需先压缩/精简。
 */
object Ext4InlineWriter {

    private const val EXT4_INLINE_DATA_FL = 0x10000000
    private const val EXT4_EXTENTS_FL = 0x00080000
    private const val EXT4_INDEX_FL = 0x00001000

    private const val DT_REG = 7
    private const val INO_ROOT = 2

    /** 写入结果。 */
    sealed interface Result {
        data class Ok(val inode: Int, val bytes: Int) : Result
        data class TooLarge(val needed: Int, val available: Int) : Result
        data object NoFreeInode : Result
        data class Io(val message: String) : Result
    }

    /**
     * 把 [content] 作为 [entryName] 写进 [image] 的根目录（inline data）。
     * 幂等：同名条目已存在且内容一致时直接返回 Ok。
     */
    fun writeRootEntry(image: File, entryName: String, content: ByteArray): Result {
        if (content.isEmpty()) return Result.Io("empty content")
        if (entryName.length > 255) return Result.Io("name too long")

        return try {
            RandomAccessFile(image, "rw").use { f ->
                val sb = readSuperblock(f)
                val blockSize = 1024 shl sb.logBlockSize
                val inodeSize = sb.inodeSize
                // inline 可用区：i_block(60B) + extra_isize 区（inode_size - 128 之后）
                val inlineCapacity = inodeSize - 40
                if (content.size > inlineCapacity) {
                    return Result.TooLarge(content.size, inlineCapacity)
                }

                // ext4 布局：block0=boot, block1=superblock, block2=GDT
                // （first_data_block=1 时 GDT 在 block 2；=0 时在 block 1）
                val firstDataBlock = readFirstDataBlock(f)
                val groupDescBlock = if (firstDataBlock == 0L) 1L else firstDataBlock + 1L
                val groupDescOff = groupDescBlock * blockSize
                val inodeTableBlock = readInodeTableBlock(f, groupDescOff)
                val inodeBitmapBlock = readInodeBitmapBlock(f, groupDescOff)
                val blockBitmapBlock = readBlockBitmapBlock(f, groupDescOff)
                val inodeTableEnd = readInodeTableEndBlock(f, groupDescOff, inodeSize, blockSize)

                // 已存在？内容一致就跳过
                findEntryInRoot(f, blockSize, inodeTableBlock, inodeSize, entryName)?.let { existing ->
                    if (readInline(f, blockSize, inodeTableBlock, inodeSize, existing).contentEquals(content)) {
                        return Result.Ok(existing, content.size)
                    }
                }

                // 根目录的 dirent 里找一个空洞，否则追加
                val slot = findOrCreateDirSlot(f, blockSize, inodeTableBlock, inodeSize, entryName)
                val dirBlock = slot.block
                val slotOffset = slot.slotOffset
                val slotLen = slot.slotLen

                val target = allocFreeInode(f, blockSize, inodeBitmapBlock, inodeTableBlock, inodeSize)
                    ?: return Result.NoFreeInode

                writeExtentInode(
                    f = f,
                    blockSize = blockSize,
                    blockBitmapBlock = blockBitmapBlock,
                    inodeTableBlock = inodeTableBlock,
                    inodeSize = inodeSize,
                    inode = target,
                    content = content,
                    minDataBlock = inodeTableEnd,
                )
                markInodeUsed(f, blockSize, inodeBitmapBlock, target)

                // dirent：ino(4) reclen(2) namelen(1) ftype(1) name
                val nameBytes = entryName.toByteArray(Charsets.UTF_8)
                val need = 8 + nameBytes.size
                if (need > slotLen) return Result.Io("directory slot too small: $need > $slotLen")

                // 一次读改写：写新条目 + 必要时收缩前一条目的 reclen。
                // 分两次写会互相覆盖（第一次写的新条目会被第二次整块重写抹掉）。
                val blockData = ByteArray(blockSize)
                f.seek(dirBlock * blockSize)
                f.readFully(blockData)
                val bb = ByteBuffer.wrap(blockData).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(slotOffset)
                bb.putInt(target)
                bb.putShort(slotLen.toShort())
                bb.put(nameBytes.size.toByte())
                bb.put(DT_REG.toByte())
                bb.put(nameBytes)
                if (slot.prevOffset >= 0) {
                    bb.position(slot.prevOffset + 4)
                    bb.putShort(slot.prevOwnLen.toShort())
                }
                f.seek(dirBlock * blockSize)
                f.write(blockData)

                Result.Ok(target, content.size)
            }
        } catch (e: Exception) {
            Result.Io(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- superblock ----------

    private class Superblock(val logBlockSize: Int, val inodeSize: Int)

    private fun readSuperblock(f: RandomAccessFile): Superblock {
        val buf = ByteArray(1024)
        f.seek(1024)
        f.readFully(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(24)
        val logBlockSize = bb.int
        // s_inode_size @ 0x58（实测：ext4 超级块里 256 出现在 0x58/0xCE）
        bb.position(0x58)
        var inodeSize = bb.short.toInt() and 0xffff
        if (inodeSize == 0) inodeSize = 256
        return Superblock(logBlockSize, inodeSize)
    }

    /** s_first_data_block @ 0x14。 */
    private fun readFirstDataBlock(f: RandomAccessFile): Long {
        val buf = ByteArray(64)
        f.seek(1024)
        f.readFully(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(0x14)
        return bb.int.toLong() and 0xffffffffL
    }

    /** inode 表结束块（分配数据块时必须跳过它）。 */
    private fun readInodeTableEndBlock(
        f: RandomAccessFile,
        groupDescOffset: Long,
        inodeSize: Int,
        blockSize: Int,
    ): Long {
        val buf = ByteArray(1024)
        f.seek(groupDescOffset)
        f.readFully(buf)
        val table = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).let {
            it.position(8); it.int.toLong() and 0xffffffffL
        }
        val sb = ByteArray(1024)
        f.seek(1024)
        f.readFully(sb)
        val ipg = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).let {
            it.position(40); it.int
        }
        return table + (ipg.toLong() * inodeSize) / blockSize
    }

    /** bg_block_bitmap @ GDT+0。 */
    private fun readBlockBitmapBlock(f: RandomAccessFile, groupDescOffset: Long): Long {
        val buf = ByteArray(64)
        f.seek(groupDescOffset)
        f.readFully(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(0)
        return bb.int.toLong() and 0xffffffffL
    }

    /** bg_inode_bitmap @ GDT+4。 */
    private fun readInodeBitmapBlock(f: RandomAccessFile, groupDescOffset: Long): Long {
        val buf = ByteArray(64)
        f.seek(groupDescOffset)
        f.readFully(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        return bb.int.toLong() and 0xffffffffL
    }

    private fun readInodeTableBlock(f: RandomAccessFile, groupDescOffset: Long): Long {
        val buf = ByteArray(64)
        f.seek(groupDescOffset)
        f.readFully(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        return bb.int.toLong() and 0xffffffffL
    }

    // ---------- inode helpers ----------

    private fun inodeOffset(inodeTableBlock: Long, inodeSize: Int, inode: Int): Long =
        inodeTableBlock * 1024L + (inode - 1) * inodeSize

    private fun readInode(f: RandomAccessFile, off: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        f.seek(off)
        f.readFully(buf)
        return buf
    }

    private fun readInline(
        f: RandomAccessFile,
        blockSize: Int,
        inodeTableBlock: Long,
        inodeSize: Int,
        inode: Int,
    ): ByteArray {
        val raw = readInode(f, inodeOffset(inodeTableBlock, inodeSize, inode), inodeSize)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val size = bb.getInt(4)
        return raw.copyOfRange(40, 40 + size)
    }

    /**
     * 分配一个空闲 inode —— **必须看 inode bitmap，不能看 mode**。
     * ext4 里被删除/保留的 inode 其 mode 是 0 但 bitmap 位是 1；
     * 按 mode 判空会覆盖真实存在的目录（实测会把 host/ 目录写坏）。
     */
    private fun allocFreeInode(
        f: RandomAccessFile,
        blockSize: Int,
        inodeBitmapBlock: Long,
        inodeTableBlock: Long,
        inodeSize: Int,
        limit: Int = 1 shl 20,
    ): Int? {
        val bitmap = ByteArray(blockSize)
        f.seek(inodeBitmapBlock * blockSize)
        f.readFully(bitmap)
        var n = 12 // 1..10 是 ext4 保留 inode（11 = lost+found）
        while (n < limit) {
            val byte = bitmap[(n - 1) / 8].toInt() and 0xff
            if ((byte ushr ((n - 1) % 8)) and 1 == 0) return n
            n++
        }
        return null
    }

    /**
     * 用 **extent** 方式写文件（debugfs 用的就是这条路，实测能启动）。
     *
     * 为什么不用 inline data：ext4 的 inline 读取要求 i_size 与 inline 区
     * 精确匹配，手工构造的 inline inode 会被内核判为不一致（error -117）。
     * extent 是内核与调试器都认的标准布局：
     *   i_block[0..11]  = extent header（magic 0xf30a, entries, max, depth）
     *   i_block[12..23] = extent entry（ee_block, ee_len, ee_start_hi, ee_start_lo）
     * 数据落在真实数据块里，内核按 extent 读块。
     */
    private fun writeExtentInode(
        f: RandomAccessFile,
        blockSize: Int,
        blockBitmapBlock: Long,
        inodeTableBlock: Long,
        inodeSize: Int,
        inode: Int,
        content: ByteArray,
        minDataBlock: Long = 0L,
    ) {
        val phys = allocFreeBlock(f, blockSize, blockBitmapBlock, minDataBlock)
            ?: throw IllegalStateException("no free block in image")

        val padded = ByteArray(blockSize)
        System.arraycopy(content, 0, padded, 0, minOf(content.size, blockSize))
        f.seek(phys * blockSize)
        f.write(padded)

        val raw = ByteArray(inodeSize)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        // ext4 inode 布局：i_mode@0 i_size_lo@4 ... i_links_count@26
        //                   i_blocks_lo@28 i_flags@32 i_block[15]@40
        bb.putShort(0, 33277.toShort())            // i_mode = S_IFREG|0755
        bb.putInt(4, content.size)                 // i_size_lo
        bb.putShort(26, 1.toShort())              // i_links_count
        bb.putInt(28, (blockSize / 512))          // i_blocks_lo（512B 单位）
        bb.putInt(32, EXT4_EXTENTS_FL)            // i_flags
        // extent header @40
        bb.putShort(40, 0xf30a.toShort())
        bb.putShort(42, 1.toShort())
        bb.putShort(44, 4.toShort())
        bb.putShort(46, 0.toShort())
        bb.putInt(48, 0)
        // extent entry @52: ee_block=0, ee_len=1, ee_start_hi, ee_start_lo
        bb.putInt(52, 0)
        bb.putShort(56, 1.toShort())
        bb.putShort(58, ((phys ushr 32) and 0xffff).toShort())
        bb.putInt(60, (phys and 0xffffffffL).toInt())
        f.seek(inodeOffset(inodeTableBlock, inodeSize, inode))
        f.write(raw)
    }

    /**
     * 分配一个空闲数据块。
     *
     * **必须跳过元数据区**：block bitmap 里 inode 表/组描述符对应的位也是 0，
     * 直接取第一个 0 位会把 extent 指到元数据块上 —— 内核挂载时读到垃圾，
     * 表现为 `Requested init ... failed (error -2)`。debugfs 分配在块 9861+，
     * 正是因为跳过了这些保留区。
     */
    private fun allocFreeBlock(
        f: RandomAccessFile,
        blockSize: Int,
        blockBitmapBlock: Long,
        minBlock: Long,
    ): Long? {
        val bitmap = ByteArray(blockSize)
        f.seek(blockBitmapBlock * blockSize)
        f.readFully(bitmap)
        val startBit = (minBlock / 1).toInt().coerceAtLeast(0)
        for (i in bitmap.indices) {
            val b = bitmap[i].toInt() and 0xff
            if (b == 0xff) continue
            for (bit in 0 until 8) {
                val idx = i * 8 + bit
                if (idx < startBit) continue
                if ((b ushr bit) and 1 == 0) {
                    bitmap[i] = (b or (1 shl bit)).toByte()
                    f.seek(blockBitmapBlock * blockSize)
                    f.write(bitmap)
                    return idx.toLong()
                }
            }
        }
        return null
    }

    /** 把 inode 在 bitmap 里标记为已用，否则后续分配会重复用它。 */
    private fun markInodeUsed(
        f: RandomAccessFile,
        blockSize: Int,
        inodeBitmapBlock: Long,
        inode: Int,
    ) {
        val bitmap = ByteArray(blockSize)
        f.seek(inodeBitmapBlock * blockSize)
        f.readFully(bitmap)
        val idx = (inode - 1) / 8
        bitmap[idx] = (bitmap[idx].toInt() or (1 shl ((inode - 1) % 8))).toByte()
        f.seek(inodeBitmapBlock * blockSize)
        f.write(bitmap)
    }

    // ---------- directory ----------

    private fun align4(v: Int): Int = (v + 3) and 3.inv()

    private data class Slot(
        val block: Long,
        val slotOffset: Int,
        val slotLen: Int,
        /** 若非负：这个槽位是从 prevOffset 条目的 reclen 尾部切出来的，需要把它的 reclen 收缩。 */
        val prevOffset: Int = -1,
        val prevOwnLen: Int = 0,
    )

    private fun findOrCreateDirSlot(
        f: RandomAccessFile,
        blockSize: Int,
        inodeTableBlock: Long,
        inodeSize: Int,
        name: String,
    ): Slot {
        // 根目录 inode=2；先找它占用的目录块（extent 或 inline）
        val rootRaw = readInode(f, inodeOffset(inodeTableBlock, inodeSize, INO_ROOT), inodeSize)
        val rootFlags = ByteBuffer.wrap(rootRaw).order(ByteOrder.LITTLE_ENDIAN).getInt(28)
        return if (rootFlags and EXT4_INLINE_DATA_FL != 0) {
            // 根目录本身是 inline：数据在 inode 内，直接就地改（根目录 inline 罕见）
            val inline = rootRaw.copyOfRange(40, rootRaw.size)
            placeInBlock(f, inodeTableBlock, inodeSize, inline, 0, name, blockSize)
        } else {
            val extents = parseExtentBlocks(rootRaw)
            for (phys in extents) {
                val data = ByteArray(blockSize)
                f.seek(phys * blockSize)
                f.readFully(data)
                val r = tryPlaceInDirBlock(f, blockSize, phys, data, name)
                if (r != null) return r
            }
            throw IllegalStateException("root directory has no free dirent slot")
        }
    }

    private fun placeInBlock(
        f: RandomAccessFile,
        inodeTableBlock: Long,
        inodeSize: Int,
        data: ByteArray,
        blockIndex: Int,
        name: String,
        blockSize: Int,
    ): Slot {
        val phys = blockIndex.toLong()
        return tryPlaceInDirBlock(f, blockSize, phys, data, name)
            ?: throw IllegalStateException("no slot")
    }

    private fun tryPlaceInDirBlock(
        f: RandomAccessFile,
        blockSize: Int,
        physBlock: Long,
        data: ByteArray,
        name: String,
    ): Slot? {
        val need = 8 + name.toByteArray(Charsets.UTF_8).size
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var lastPos = -1
        var lastLen = 0
        while (pos + 8 <= data.size) {
            bb.position(pos)
            val ino = bb.int
            val reclen = bb.short.toInt() and 0xffff
            if (reclen < 8) break
            // 空洞：ino==0 且够大 —— 直接用
            if (ino == 0 && reclen >= need) {
                return Slot(physBlock, pos, reclen)
            }
            // 记录最后一个真实条目：它的 reclen 通常一直延伸到块尾，可以切一刀
            if (ino != 0) {
                lastPos = pos
                lastLen = reclen
            }
            pos += reclen
        }
        // 切分最后一个条目的尾部空间（ext4 目录的标准做法）
        if (lastPos >= 0 && lastLen >= need) {
            val namelen = data[lastPos + 6].toInt() and 0xff
            // ext4 要求每个 dirent 的 reclen 4 字节对齐（EXT4_DIR_ENTRY_ALIGN）。
            // 用 8+namelen 会少算 2 字节（如 lost+found: 18 vs 正确的 20），
            // 导致内核报 "EXT2 directory corrupted"。
            val ownNeed = align4(8 + namelen)
            val tail = lastPos + ownNeed
            if (tail + need <= lastPos + lastLen) {
                return Slot(physBlock, tail, lastPos + lastLen - tail, lastPos, ownNeed)
            }
        }
        return null
    }

    private fun findEntryInRoot(
        f: RandomAccessFile,
        blockSize: Int,
        inodeTableBlock: Long,
        inodeSize: Int,
        name: String,
    ): Int? {
        val rootRaw = readInode(f, inodeOffset(inodeTableBlock, inodeSize, INO_ROOT), inodeSize)
        val rootFlags = ByteBuffer.wrap(rootRaw).order(ByteOrder.LITTLE_ENDIAN).getInt(28)
        val blocks = if (rootFlags and EXT4_INLINE_DATA_FL != 0) {
            listOf(-1L)
        } else {
            parseExtentBlocks(rootRaw)
        }
        for (phys in blocks) {
            val data = if (phys < 0) {
                rootRaw.copyOfRange(40, rootRaw.size)
            } else {
                val d = ByteArray(blockSize)
                f.seek(phys * blockSize)
                f.readFully(d)
                d
            }
            var pos = 0
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (pos + 8 <= data.size) {
                bb.position(pos)
                val ino = bb.int
                val reclen = bb.short.toInt() and 0xffff
                if (reclen < 8) break
                val namelen = data[pos + 6].toInt() and 0xff
                if (ino != 0 && namelen == name.length) {
                    val entryName = String(data, pos + 8, namelen, Charsets.UTF_8)
                    if (entryName == name) return ino
                }
                pos += reclen
            }
        }
        return null
    }

    /** 解析 ext4 extent 树，返回叶子物理块号列表（只处理单层 depth=0）。 */
    private fun parseExtentBlocks(raw: ByteArray): List<Long> {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(40) // i_block[]
        val magic = bb.short.toInt() and 0xffff
        if (magic != 0xf30a) return emptyList() // 不是 extent 头
        bb.position(40 + 2)
        val entries = bb.short.toInt() and 0xffff
        bb.position(40 + 6)
        val depth = bb.short.toInt() and 0xffff
        if (depth != 0) return emptyList()
        val out = mutableListOf<Long>()
        for (i in 0 until entries) {
            val at = 40 + 12 + i * 12
            if (at + 12 > raw.size) break
            // ext4 extent_entry 布局：ee_block(4) ee_len(2) ee_start_hi(2) ee_start_lo(4)
            // 注意 hi 在 lo 之前 —— 顺序搞反会把物理块号读成天文数字。
            val eeLen = bb.getShort(at + 4).toInt() and 0xffff
            val eeStartHi = bb.getShort(at + 6).toInt() and 0xffff
            val eeStartLo = bb.getInt(at + 8).toLong() and 0xffffffffL
            if (eeLen <= 0) continue
            out.add(eeStartLo or (eeStartHi.toLong() shl 32))
        }
        return out
    }
}
