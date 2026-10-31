package top.tianyan.app.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import top.tianyan.app.ui.settings.permission.KeepaliveTopic
import top.tianyan.app.ui.settings.permission.OemBrand
import top.tianyan.app.ui.settings.permission.PermissionGuideRepository

/** 由导航装配复用厂商指引，避免 onboarding 横向依赖 settings。 */
fun Context.openOnboardingAutostartSettings(): Boolean {
    val intents = PermissionGuideRepository.buildLaunchIntents(this, OemBrand.detect(), KeepaliveTopic.AUTOSTART)
    for (intent in intents) {
        try {
            startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // 厂商入口不存在时继续尝试应用详情。
        } catch (_: SecurityException) {
            // 部分 ROM 不允许第三方打开管家入口。
        }
    }
    return false
}
