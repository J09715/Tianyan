// language: Kotlin, module: feature/components, test: JUnit4 + Robolectric
package top.tianyan.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [classifyError] 的分类契约。
 *
 * 这套判定是错误态 UI 的入口：分类错了，用户看到的标题、图标、是否给「重试」全错。
 * 因此这里锁住三件事：
 *
 * 1. **优先级**：权限类必须在网络类之前判（EPERM 的 message 里可能带 socket 字样）。
 * 2. **可重试性**：权限/认证/空间不足不可重试，反复点重试是恶意。
 * 3. **不丢信息**：未知类型不能吞掉原始 message。
 */
class ErrorClassificationTest {

    @Test
    fun `权限错误优先于网络错误判定`() {
        val error = RuntimeException("java.net.SocketException: EACCES permission denied")
        assertEquals(ErrorKind.Permission, classifyError(error))
    }

    @Test
    fun `磁盘空间不足识别为 Storage`() {
        val error = RuntimeException("write failed: No space left on device")
        assertEquals(ErrorKind.Storage, classifyError(error))
    }

    @Test
    fun `超时识别为 Timeout`() {
        val error = RuntimeException("SocketTimeoutException: Read timed out")
        assertEquals(ErrorKind.Timeout, classifyError(error))
    }

    @Test
    fun `认证失败识别为 Auth`() {
        val error = RuntimeException("HTTP 401 Unauthorized: invalid api key")
        assertEquals(ErrorKind.Auth, classifyError(error))
    }

    @Test
    fun `网络不可达识别为 Network`() {
        val error = RuntimeException("UnknownHostException: api.openai.com")
        assertEquals(ErrorKind.Network, classifyError(error))
    }

    @Test
    fun `沙箱相关失败识别为 Runtime`() {
        val error = RuntimeException("proot exited with code 1")
        assertEquals(ErrorKind.Runtime, classifyError(error))
    }

    @Test
    fun `JSON 解析失败识别为 Invalid`() {
        val error = RuntimeException("kotlinx.serialization.json.internal.JsonDecodingException: unexpected token")
        assertEquals(ErrorKind.Invalid, classifyError(error))
    }

    @Test
    fun `空异常归为 Unknown 而不是抛异常`() {
        assertEquals(ErrorKind.Unknown, classifyError(null))
    }

    @Test
    fun `完全陌生的异常归为 Unknown`() {
        val error = IllegalStateException("完全无法归类的东西")
        assertEquals(ErrorKind.Unknown, classifyError(error))
    }

    @Test
    fun `不可重试的错误类型不提供重试出路`() {
        // 权限、认证、空间不足三类：给「重试」是让用户做无用功
        assertTrue(!ErrorKind.Permission.isRetryable())
        assertTrue(!ErrorKind.Auth.isRetryable())
        assertTrue(!ErrorKind.Storage.isRetryable())
        // 网络、超时可重试
        assertTrue(ErrorKind.Network.isRetryable())
        assertTrue(ErrorKind.Timeout.isRetryable())
    }

    @Test
    fun `不同错误类型呈现不同标题`() {
        val networkTitle = ErrorKind.Network.presentationTitle()
        val authTitle = ErrorKind.Auth.presentationTitle()
        assertNotEquals(networkTitle, authTitle)
        assertTrue(networkTitle.isNotBlank())
        assertTrue(authTitle.isNotBlank())
    }
}
