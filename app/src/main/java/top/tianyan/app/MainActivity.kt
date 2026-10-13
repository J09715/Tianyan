package top.tianyan.app

import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.onboarding.OnboardingScreen
import top.tianyan.app.ui.onboarding.OnboardingViewModel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import top.tianyan.app.core.datastore.AppearancePreferences
import top.tianyan.app.runtime.service.RuntimeServiceController
import top.tianyan.app.ui.navigation.TianyanNavHost
import top.tianyan.app.ui.theme.TianyanTheme
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

import javax.inject.Inject
import top.tianyan.app.core.common.navigation.AppNavigationTarget
import top.tianyan.app.core.common.navigation.GlobalNavigationBus
import top.tianyan.app.service.adb.AdbNotificationManager

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var settingsDataStore: AppearancePreferences

    @Inject
    lateinit var runtimeServiceController: RuntimeServiceController

    @Inject
    lateinit var globalNavigationBus: GlobalNavigationBus

    @Inject
    lateinit var adbNotificationManager: AdbNotificationManager

    @Inject
    lateinit var gitCredentialIpcBridge: top.tianyan.app.runtime.credentials.GitCredentialIpcBridge

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) runtimeServiceController.start()
    }

    private var notificationPermissionCheckScheduled = false

    /** 控制 SplashScreen 持续显示，直到 onboarding 偏好从 DataStore 加载完成，避免白屏空窗。 */
    private val keepSplashOnScreen: MutableState<Boolean> = mutableStateOf(true)

    private var createdUptimeMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        createdUptimeMs = android.os.SystemClock.uptimeMillis()
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen.value }
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        enableEdgeToEdge()
        // 冷启动先把外观设置同步读出来，首帧就用真实值。
        //
        // 否则这三个值都要等 DataStore 异步回调：首帧按「玄同 + 无壁纸 + 缩放 1.0」渲染，
        // 读完后整棵树再按真实值重排一次——用户看到的是「启动时界面不对，过一会自己好了」，
        // 澄明主题下尤其明显（背景层重建 + 覆盖层渐变突变 + LocalDensity 变化触发全量重测量）。
        // 只读一次，后续仍由 Flow 驱动，设置页改动照常实时生效。
        val initialAppearance = runBlocking {
            runCatching { settingsDataStore.appearanceSnapshot() }.getOrNull()
        }
        setContent {
            val themeMode by settingsDataStore.themeMode
                .collectAsStateWithLifecycle(initialValue = initialAppearance?.themeMode ?: "system")
            val themeStyle by settingsDataStore.themeStyle
                .collectAsStateWithLifecycle(initialValue = initialAppearance?.themeStyle ?: "xuantong")
            val chengmingBackgroundUri by settingsDataStore.chengmingBackgroundUri
                .collectAsStateWithLifecycle(initialValue = initialAppearance?.chengmingBackgroundUri)
            val pageScale by settingsDataStore.appFontScale
                .collectAsStateWithLifecycle(initialValue = initialAppearance?.appFontScale ?: 1f)
            val systemDark = isSystemInDarkTheme()
            val systemDensity = LocalDensity.current
            val isDark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }

            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = systemDensity.density * pageScale.coerceIn(0.8f, 1.3f),
                    fontScale = systemDensity.fontScale,
                ),
            ) {
                TianyanTheme(
                    style = top.tianyan.app.ui.theme.ThemeStyle.fromId(themeStyle),
                    darkTheme = isDark,
                    backgroundUri = chengmingBackgroundUri,
                ) {
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                val onboarding by onboardingViewModel.status.collectAsStateWithLifecycle()

                // onboarding 偏好读盘完成后让 SplashScreen 退场；Runtime 恢复已在后台继续，不阻塞首帧。
                // Linux 环境恢复（restoreInstalledState）由 OnboardingViewModel.init 自行发起，不再在此重复触发。
                LaunchedEffect(onboarding.loaded) {
                    if (onboarding.loaded && keepSplashOnScreen.value) {
                        keepSplashOnScreen.value = false
                        android.util.Log.i(
                            "TianyanStartup",
                            "splash dismissed in ${android.os.SystemClock.uptimeMillis() - createdUptimeMs}ms",
                        )
                    }
                }

                when {
                    !onboarding.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    onboarding.completed -> TianyanNavHost(globalNavigationBus = globalNavigationBus)
                    else -> OnboardingScreen(onboardingViewModel)
                }
                // 全局 git 凭据弹窗宿主：容器 helper 走文件 IPC 请求凭据时，无论在哪个页面都能立即弹出。
                top.tianyan.app.ui.chat.GlobalCredentialDialogHost(gitCredentialIpcBridge)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 主应用回到前台时，自动关闭智枢桌面悬浮小窗，避免主界面与悬浮窗重叠
        runCatching {
            top.tianyan.app.ui.chat.floating.FloatingChatService.stop(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(targetIntent: Intent?) {
        val intentToHandle = targetIntent ?: intent ?: return
        val action = intentToHandle.action
        val navigateTo = intentToHandle.getStringExtra("navigate_to")
        val isAdbLogcat = action == "top.tianyan.app.action.OPEN_ADB_LOGCAT" || navigateTo == "adb_logcat"
        if (isAdbLogcat) {
            globalNavigationBus.navigateTo(top.tianyan.app.core.common.navigation.AppNavigationTarget.AdbLogcat)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (notificationPermissionCheckScheduled) return
        notificationPermissionCheckScheduled = true

        // Runtime permission dialogs are most reliable after the first page is resumed and drawn.
        // First launch also restores onboarding/theme state, so requesting from onCreate can be
        // swallowed by some Android builds before the Activity becomes fully interactive.
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                // 首帧之后再拉起 Runtime 保活前台服务，避免 onStartCommand 抢在首帧前占用主线程。
                runtimeServiceController.start()
                requestNotificationPermissionIfNeeded()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
