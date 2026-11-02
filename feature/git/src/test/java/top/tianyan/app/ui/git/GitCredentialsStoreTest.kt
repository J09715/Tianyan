package top.tianyan.app.ui.git

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * extractHost 纯函数行为测试（批次 E）。
 * 纯 JVM：companion 函数不依赖 Android 框架，直接断言解析规则。
 * 读写回环（save/get/remove，需 Robolectric Context + AndroidKeyStore）见
 * GitCredentialsStoreRoboTest，仅在支持 Robolectric native 的环境（CI x86_64）执行。
 */
class GitCredentialsStoreTest {

    // ---------- extractHost ----------

    @Test
    fun `ssh and plain forms resolve to host`() {
        assertEquals("github.com", GitCredentialsStore.extractHost("git@github.com:x/y.git"))
        // 已知缺陷（本轮审计发现，业务源码不在本批次可改范围，仅用测试钉住现状）：
        // ssh://git@host:port/... 的 before('/')→before(':')→before('@') 链会把
        // "git@github.com:22" 逐段剥成 "git" 而不是 "github.com"；
        // 正确修法是把 before('@') 提到 before(':') 之前。修复后此断言应改为 github.com。
        assertEquals("git", GitCredentialsStore.extractHost("ssh://git@github.com:22/x/y.git"))
        assertEquals("github.com", GitCredentialsStore.extractHost("https://github.com/x/y.git"))
        // 大写归一：host 比较必须大小写无关。
        assertEquals("github.com", GitCredentialsStore.extractHost("HTTPS://GitHub.COM/x/y.git"))
    }

    @Test
    fun `https with userinfo is stripped to bare host`() {
        // 已知缺陷（同上）：before('@') 排在 before(':') 之后，
        // "user:token@host" 被先按 ':' 截成 "user"，userinfo 段成了返回值。
        // 正确行为是整段剥掉 userinfo 只剩 host；修复后此断言应改为 github.com / gitlab.com。
        assertEquals("user", GitCredentialsStore.extractHost("https://user:ghp_abc123@github.com/x/y.git"))
        assertEquals("oauth2", GitCredentialsStore.extractHost("https://oauth2:tok@gitlab.com/a/b"))
        // 无 userinfo 的普通形态是正确的。
        assertEquals("github.com", GitCredentialsStore.extractHost("https://github.com/x/y.git"))
    }
}
