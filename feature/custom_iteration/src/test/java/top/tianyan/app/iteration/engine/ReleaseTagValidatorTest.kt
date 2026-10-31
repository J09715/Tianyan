package top.tianyan.app.iteration.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** ReleaseTagValidator 纯 JVM 单元测试。 */
class ReleaseTagValidatorTest {

    @Test
    fun nullAndBlankEqualNoReleaseCreated() {
        assertNull(ReleaseTagValidator.validate(null))
        assertNull(ReleaseTagValidator.validate(""))
        assertNull(ReleaseTagValidator.validate("   "))
    }

    @Test
    fun standardVersionAndDevSuffixAreValid() {
        assertNull(ReleaseTagValidator.validate("v0.2.1-dev"))
        assertNull(ReleaseTagValidator.validate("v1.0.0"))
        assertNull(ReleaseTagValidator.validate(" v10.20.30-rc.1 "))
    }

    @Test
    fun missingVOrIncompleteVersionIsRejected() {
        assertNotNull(ReleaseTagValidator.validate("0.2.1-dev"))
        assertNotNull(ReleaseTagValidator.validate("v0.2"))
        assertNotNull(ReleaseTagValidator.validate("v0.2.1-"))
        // 下划线后缀故意拒绝，与工作流 bash 守卫同一形状。
        assertNotNull(ReleaseTagValidator.validate("v1.0.0_rc1"))
    }

    @Test
    fun metaCharactersAndChineseSuffixAreRejected() {
        assertNotNull(ReleaseTagValidator.validate("v0.2.1-dev; rm -rf /"))
        assertNotNull(ReleaseTagValidator.validate("v0.2.1-dev$(whoami)"))
        assertNotNull(ReleaseTagValidator.validate("v0.2.1 开发版"))
    }

    @Test
    fun rejectionMessageCarriesOriginalInput() {
        val msg = ReleaseTagValidator.validate("bad-tag")
        assertEquals(
            "无效的 release tag \"bad-tag\"：应为 v<主>.<次>.<补丁>[-后缀]，如 v0.2.1-dev",
            msg,
        )
    }
}
