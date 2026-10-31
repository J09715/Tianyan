package top.tianyan.app.runtime.browser.inject

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * VConsoleInstaller 注入脚本契约：
 * - assets/vconsole.min.js 真实存在且可读（文件名 / 打包配置出错在此暴露）；
 * - fullScript = 库 + 引导 IIFE，引导脚本带幂等守卫与 DOM-ready 等待（document-start 时 body 不存在）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VConsoleInstallerTest {

    @Test
    fun `fullScript bundles vconsole library and bootstrap`() {
        val installer = VConsoleInstaller(ApplicationProvider.getApplicationContext())
        val script = installer.fullScript
        // 库部分：vConsole 头注释（版本钉住，防止误换成不兼容版本后无人知晓）
        assertTrue(script.contains("vConsole v3.15.1"))
        // 引导部分：幂等守卫 + DOMContentLoaded 等待 + 实例化
        assertTrue(script.contains("window.__tianyanVConsole"))
        assertTrue(script.contains("DOMContentLoaded"))
        assertTrue(script.contains("new VConsole()"))
        // 引导 IIFE 必须在库之后（VConsole 全局先定义后使用）
        assertTrue(script.indexOf("new VConsole()") > script.indexOf("vConsole v3.15.1"))
    }

    @Test
    fun `bootstrap is idempotent and waits for DOM`() {
        val bootstrap = VConsoleInstaller.BOOTSTRAP
        // 幂等守卫：先查 marker 再设 marker，双注无害
        assertTrue(bootstrap.contains("if (window.__tianyanVConsole) return;"))
        assertTrue(bootstrap.contains("window.__tianyanVConsole = true;"))
        // DOM 未就绪时挂 once 监听，否则直接 boot（降级路径 onPageStarted 时 readyState 已过 loading）
        assertTrue(bootstrap.contains("document.readyState === 'loading'"))
        assertTrue(bootstrap.contains("addEventListener('DOMContentLoaded', boot, { once: true })"))
        // 页面环境异常不得外泄：new VConsole() 包在 try/catch 内
        assertTrue(bootstrap.contains("try {"))
        assertTrue(bootstrap.contains("} catch (e) {}"))
    }
}
