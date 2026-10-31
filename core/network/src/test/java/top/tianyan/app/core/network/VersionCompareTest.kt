package top.tianyan.app.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun `正式版高于旧数字版本`() {
        assertTrue(VersionCompare.isNewer("1.0.0", "0.23.1"))
        assertFalse(VersionCompare.isNewer("0.23.1", "1.0.0"))
    }

    @Test
    fun `rc 用户可升级到正式版`() {
        assertTrue(VersionCompare.isNewer("1.0.0", "1.0.0-rc.1"))
        assertTrue(VersionCompare.isNewer("1.0.0", "1.0.0-rc.9"))
    }

    @Test
    fun `正式版不降级到 rc`() {
        assertFalse(VersionCompare.isNewer("1.0.0-rc.1", "1.0.0"))
    }

    @Test
    fun `rc 迭代按序号数值比较`() {
        assertTrue(VersionCompare.isNewer("1.0.0-rc.2", "1.0.0-rc.1"))
        assertTrue(VersionCompare.isNewer("1.0.0-rc.10", "1.0.0-rc.2"))
        assertFalse(VersionCompare.isNewer("1.0.0-rc.1", "1.0.0-rc.2"))
    }

    @Test
    fun `相同版本不提示更新`() {
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0-rc.1", "1.0.0-rc.1"))
    }

    @Test
    fun `prerelease 标识符按 semver 规则比较`() {
        // beta < rc
        assertTrue(VersionCompare.isNewer("1.0.0-rc.1", "1.0.0-beta.1"))
        // 缺段更小：rc < rc.1
        assertFalse(VersionCompare.isNewer("1.0.0-rc", "1.0.0-rc.1"))
    }

    @Test
    fun `空白版本不提示更新`() {
        assertFalse(VersionCompare.isNewer("", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", ""))
    }
}
