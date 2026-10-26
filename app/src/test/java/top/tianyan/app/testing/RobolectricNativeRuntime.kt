package top.tianyan.app.testing

import java.lang.reflect.Method
import org.junit.Assume.assumeTrue
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.Sandbox

/**
 * Robolectric 的 native runtime 只提供 x86-64 / macOS 构建。Linux aarch64 上
 * [org.robolectric.nativeruntime.DefaultNativeRuntimeLoader] 会在 runner 的
 * `beforeTest` 阶段直接抛 `AssertionError: The Robolectric native runtime is not
 * supported on Linux (aarch64)`，此时 JUnit 的 `@Before` 还没执行，因此守卫必须放在
 * runner 层。
 *
 * 天衍的主要开发场景是手机上的 Linux 沙盒（aarch64），本地跑单测时这组用例必然全红，
 * 会掩盖真实回归。这里改为跳过，x86-64 CI 上照常执行。
 */
object RobolectricNativeRuntime {

    /** 当前 JVM 是否处于「Robolectric native runtime 不可用」的平台。 */
    fun isUnsupported(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val isLinux = os.contains("linux")
        val isAarch64 = arch == "aarch64" || arch == "arm64"
        return isLinux && isAarch64
    }
}

/** 在 native runtime 不可用的平台上跳过用例的 [RobolectricTestRunner]。 */
class Aarch64AwareRobolectricTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {

    @Throws(Throwable::class)
    override fun beforeTest(sandbox: Sandbox, method: FrameworkMethod, bootstrappedMethod: Method) {
        assumeTrue(
            "Robolectric native runtime is not available on Linux aarch64; " +
                "skipping on-device PRoot sandbox runs.",
            !RobolectricNativeRuntime.isUnsupported(),
        )
        super.beforeTest(sandbox, method, bootstrappedMethod)
    }
}
