package top.tianyan.app.runtime.uml

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UmlManifest 解析验证：清单是「产物从哪来、怎么校验」的唯一真源，
 * 解析错了会导致下到错的东西或校验被跳过，所以这条链路必须有测试兜底。
 */
class UmlManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
    {
      "manifest_version": 1,
      "core": { "version": "chroot-main", "chroot64": { "url": "https://x/chroot64.tar.gz", "sha256": "aa", "size": 1 } },
      "rootless": {
        "version": "rootless-main",
        "note": "dev ships these inside the APK",
        "qemu": { "url": "https://x/qemu", "sha256": "bb", "size": 43800304 },
        "kernel": { "url": "https://x/Image", "sha256": "cc", "size": 37605312 },
        "initrd": { "url": "https://x/initrd.img", "sha256": "dd", "size": 38301815 },
        "libslirp": { "url": "https://x/libslirp.so", "sha256": "ee", "size": 1145496 },
        "rootfs": { "url": "https://x/rootfs.imgz", "sha256": "ff", "size": 427974567 }
      },
      "app": { "versionCode": 651, "versionName": "6.5.1" },
      "news": [ { "id": 3, "title": "Stryker 6.5" } ]
    }
    """.trimIndent()

    @Test
    fun parsesRootlessRootfs() {
        val manifest = json.decodeFromString<UmlManifest>(sample)
        assertEquals(1, manifest.manifestVersion)
        assertEquals("rootless-main", manifest.rootless.version)
        val rootfs = manifest.rootfs
        assertTrue("rootfs asset must be parsed", rootfs != null)
        assertEquals("https://x/rootfs.imgz", rootfs!!.url)
        assertEquals("ff", rootfs.sha256)
        assertEquals(427_974_567L, rootfs.size)
    }

    @Test
    fun ignoresUnrelatedBlocks() {
        val manifest = json.decodeFromString<UmlManifest>(sample)
        // core/app/news 段天衍不消费，但必须能忽略（ignoreUnknownKeys），不能解析失败
        assertTrue(manifest.rootless.qemu != null)
        assertTrue(manifest.rootless.kernel != null)
        assertNull(manifest.rootless.rootfs?.url?.takeIf { it.isBlank() })
    }

    @Test
    fun unusableRootfsIsRejected() {
        // 非 HTTPS 的 URL 视为不可用 —— 防止清单被篡改后下到明文地址
        val bad = """
            { "manifest_version": 1,
              "rootless": { "rootfs": { "url": "http://evil/rootfs.imgz", "sha256": "x", "size": 1 } } }
        """.trimIndent()
        val manifest = json.decodeFromString<UmlManifest>(bad)
        assertNull("non-https rootfs must not be accepted", manifest.rootfs)
        assertFalse(manifest.rootless.rootfs!!.isUsable)
    }

    @Test
    fun assetChecksumPresenceIsExposed() {
        val withChecksum = UmlAsset(url = "https://x/r.imgz", sha256 = "abc", size = 1)
        val withoutChecksum = UmlAsset(url = "https://x/r.imgz", sha256 = "", size = 0)
        assertTrue(withChecksum.hasChecksum)
        assertFalse(withoutChecksum.hasChecksum)
    }
}
