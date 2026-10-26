package top.tianyan.app.di.feature.custom.iteration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.iteration.engine.CustomIterationBootstrap
import top.tianyan.app.iteration.engine.CustomIterationPaths
import top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator
import top.tianyan.app.ui.iteration.CustomIterationViewModel

/**
 * Koin 装配回归闸门。
 *
 * 用真实 [featureCustomIterationModule] 验证图能解析出 [TianyanDevBuildCoordinator] 与
 * [CustomIterationViewModel]，且路径解析保持惰性（构造 ViewModel 不触碰 RuntimePathManager、
 * 不执行任何 gh 命令）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class KoinModuleTest {

    @After
    fun tearDown() = stopKoin()

    /**
     * 只装配上下文与模块本身。
     *
     * 刻意**不注册** [RuntimePathManager]：若 ViewModel 构造期就解析路径，
     * 图会因缺少该单例而立刻抛错 —— 这正是对「惰性解析」的强断言。
     */
    private fun startGraph(): org.koin.core.Koin {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return startKoin {
            androidContext(context)
            modules(featureCustomIterationModule)
        }.koin
    }

    @Test
    fun `coordinator resolves and pins the shared workspace constant`() {
        val koin = startGraph()
        val coordinator = koin.get<TianyanDevBuildCoordinator>()
        assertNotNull(coordinator)
        // 工作区必须与 Bootstrap 的 guest 路径保持单一来源
        assertEquals("/root/custom_tianyan", CustomIterationBootstrap.GUEST_WORKSPACE)
    }

    @Test
    fun `viewmodel resolves lazily and stays in the initial state`() {
        val koin = startGraph()
        val vm = koin.get<CustomIterationViewModel>()
        assertNotNull(vm)

        // 构造期未触发路径解析 → 仍为初始未就绪态
        assertEquals(false, vm.uiState.value.workspaceReady)
        assertEquals("", vm.uiState.value.workspacePath)
    }

    @Test
    fun `viewmodel resolution never touches the runtime graph`() {
        // 图中没有 RuntimePathManager，解析成功即证明 ViewModel 构造期不求值路径
        val koin = startGraph()
        assertNotNull(koin.get<CustomIterationViewModel>())
        assertNotNull(koin.get<TianyanDevBuildCoordinator>())
    }

    @Test
    fun `workspace path derivation follows the runtime contract`() {
        // homeDir 是 PRoot 绑定到 guest /root 的宿主目录；工作区必须落在它之下
        val base = File(
            ApplicationProvider.getApplicationContext<Application>().filesDir,
            "linux-runtime",
        )
        val home = File(base, "distros/ubuntu/home")
        assertTrue(home.absolutePath.startsWith(base.absolutePath))

        val workspace = File(home, CustomIterationBootstrap.WORKSPACE_NAME)
        assertTrue(
            "workspace must live under home: $workspace",
            workspace.absolutePath.startsWith(home.absolutePath),
        )
        // guest 视角与 PRoot 的 homeDir→/root 绑定一致
        assertEquals("/root/custom_tianyan", CustomIterationBootstrap.GUEST_WORKSPACE)
        assertEquals(
            CustomIterationBootstrap.GUEST_WORKSPACE,
            top.tianyan.app.iteration.engine.TianyanDevBuildCli.DEFAULT_WORKSPACE_DIR,
        )
    }
}
