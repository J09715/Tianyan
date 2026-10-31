package top.tianyan.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储挂载绑定安全校验规则测试。
 *
 * 规则同时被三处消费：PRoot 命令构建（require 防逃逸）、运行时挂载过滤（防崩溃）、
 * 设置页保存/展示校验（防新增脏数据），必须保持语义一致。
 * 背景：v0.19.0 曾因设置页可保存越界 guestPath，导致会话启动时
 * ProotCommandBuilder 抛 IllegalArgumentException 闪退（用户反馈 2026-09-28）。
 */
class StorageMountBindingTest {

    private fun binding(hostPath: String, guestPath: String) = StorageMountBinding(
        id = "test",
        name = "test",
        hostPath = hostPath,
        guestPath = guestPath,
        enabled = true,
        isSystemDefault = false,
    )

    // ---- normalizeGuestPath ----

    @Test
    fun normalizeCollapsesEmptySegmentsAndDots() {
        assertEquals("/mnt", StorageMountBinding.normalizeGuestPath("/mnt/"))
        assertEquals("/mnt/a/b", StorageMountBinding.normalizeGuestPath("/mnt//a/./b/"))
        assertEquals("/", StorageMountBinding.normalizeGuestPath("/"))
        assertEquals("/", StorageMountBinding.normalizeGuestPath("///"))
    }

    @Test
    fun normalizeRejectsRelativeAndParentTraversal() {
        assertNull(StorageMountBinding.normalizeGuestPath("mnt/folder"))
        assertNull(StorageMountBinding.normalizeGuestPath("/mnt/../etc"))
        assertNull(StorageMountBinding.normalizeGuestPath("/mnt/.."))
        assertNull(StorageMountBinding.normalizeGuestPath("/a/../.."))
    }

    // ---- isGuestPathAllowed ----

    @Test
    fun allowsMntAndSdcardRoots() {
        assertTrue(StorageMountBinding.isGuestPathAllowed("/mnt"))
        assertTrue(StorageMountBinding.isGuestPathAllowed("/mnt/"))
        assertTrue(StorageMountBinding.isGuestPathAllowed("/mnt/my_folder"))
        assertTrue(StorageMountBinding.isGuestPathAllowed("/sdcard"))
        assertTrue(StorageMountBinding.isGuestPathAllowed("/sdcard/Download"))
        assertTrue(StorageMountBinding.isGuestPathAllowed("/sdcard/Documents/nested"))
    }

    @Test
    fun rejectsGuestPathsOutsideAllowedRoots() {
        // 用户实际会输入的越界路径（v0.19.0 崩溃触发条件）
        assertFalse(StorageMountBinding.isGuestPathAllowed("/downloads"))
        assertFalse(StorageMountBinding.isGuestPathAllowed("/root/data"))
        assertFalse(StorageMountBinding.isGuestPathAllowed("/workspace/project"))
        assertFalse(StorageMountBinding.isGuestPathAllowed("/opt/tianyan"))
        assertFalse(StorageMountBinding.isGuestPathAllowed("/"))
        // 前缀相似但不是允许根本身
        assertFalse(StorageMountBinding.isGuestPathAllowed("/mntx/foo"))
        assertFalse(StorageMountBinding.isGuestPathAllowed("/sdcard2/foo"))
        // 越界逃逸写法
        assertFalse(StorageMountBinding.isGuestPathAllowed("/mnt/../root"))
        assertFalse(StorageMountBinding.isGuestPathAllowed("relative/path"))
    }

    // ---- isHostPathAllowed ----

    @Test
    fun allowsSharedStorageHostPaths() {
        assertTrue(StorageMountBinding.isHostPathAllowed("/storage/emulated/0"))
        assertTrue(StorageMountBinding.isHostPathAllowed("/storage/emulated/0/Download"))
        assertTrue(StorageMountBinding.isHostPathAllowed("/storage/emulated/0/DCIM/Camera"))
    }

    @Test
    fun rejectsHostPathsOutsideSharedStorage() {
        assertFalse(StorageMountBinding.isHostPathAllowed("/data/data/top.tianyan.app"))
        assertFalse(StorageMountBinding.isHostPathAllowed("/sdcard"))
        assertFalse(StorageMountBinding.isHostPathAllowed("/storage/emulated"))
    }

    // ---- validationError ----

    @Test
    fun systemDefaultBindingsAreValid() {
        assertNull(
            StorageMountBinding.validationError(
                binding("/storage/emulated/0", "/sdcard"),
            ),
        )
        assertNull(
            StorageMountBinding.validationError(
                binding("/storage/emulated/0/Download", "/sdcard/Download"),
            ),
        )
        assertNull(
            StorageMountBinding.validationError(
                binding("/storage/emulated/0/Documents", "/sdcard/Documents"),
            ),
        )
    }

    @Test
    fun validCustomBindingPasses() {
        assertNull(
            StorageMountBinding.validationError(
                binding("/storage/emulated/0/Pictures", "/mnt/pictures"),
            ),
        )
    }

    @Test
    fun rejectsIllegalCharacters() {
        assertEquals(
            "宿主挂载路径包含非法字符",
            StorageMountBinding.validationError(binding("/storage/emulated/0/a:b", "/mnt/x")),
        )
        assertEquals(
            "容器挂载路径包含非法字符",
            StorageMountBinding.validationError(binding("/storage/emulated/0/Download", "/mnt/a:b")),
        )
    }

    @Test
    fun rejectsGuestPathMessages() {
        assertEquals(
            "容器挂载路径必须是绝对路径",
            StorageMountBinding.validationError(binding("/storage/emulated/0/Download", "mnt/x")),
        )
        assertEquals(
            "容器挂载路径不允许包含 ..",
            StorageMountBinding.validationError(binding("/storage/emulated/0/Download", "/mnt/../etc")),
        )
        // v0.19.0 闪退的直接触发消息
        assertEquals(
            "容器挂载仅允许位于 /mnt 或 /sdcard 内",
            StorageMountBinding.validationError(binding("/storage/emulated/0/Download", "/downloads")),
        )
        assertEquals(
            "容器挂载仅允许位于 /mnt 或 /sdcard 内",
            StorageMountBinding.validationError(binding("/storage/emulated/0/Download", "/root")),
        )
    }

    @Test
    fun rejectsHostPathOutsideSharedStorage() {
        assertEquals(
            "宿主挂载仅允许位于 /storage/emulated/0 内",
            StorageMountBinding.validationError(binding("/data/data/top.tianyan.app", "/sdcard/escape")),
        )
    }
}
