package top.tianyan.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.drawable.Animatable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.core.splashscreen.SplashScreen

/**
 * 天衍启动页动效辅助器。
 * 遵循 Material 3 Expressive 设计规范，提供类似 Gmail 的丝滑启动退场动效：
 * 1. 矢量动画合并完成且数据就绪后立刻退场，绝不无故死等；
 * 2. 中央 Logo 图标向前快速放大穿透并淡出（280ms）；
 * 3. 全屏启动背景平滑淡出，主界面即刻呈现。
 */
fun SplashScreen.setupTianyanSplashAnimation(
    keepOnScreen: () -> Boolean,
) {
    setKeepOnScreenCondition { keepOnScreen() }
    setOnExitAnimationListener { splashScreenViewProvider ->
        val splashView = splashScreenViewProvider.view
        val iconView = splashScreenViewProvider.iconView

        // 兼容性启动：若部分设备未自动触发矢量动画，主动启动
        (iconView as? ImageView)?.drawable?.let { drawable ->
            (drawable as? Animatable)?.start()
        }

        // Gmail 标志性穿透退出动效：Logo 向前放大穿梭并渐隐，背景平滑淡出揭幕主界面
        val scaleX = ObjectAnimator.ofFloat(iconView, View.SCALE_X, 1.0f, 1.30f)
        val scaleY = ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 1.0f, 1.30f)
        val iconAlpha = ObjectAnimator.ofFloat(iconView, View.ALPHA, 1.0f, 0.0f)
        val splashAlpha = ObjectAnimator.ofFloat(splashView, View.ALPHA, 1.0f, 0.0f)

        AnimatorSet().apply {
            duration = 280L
            interpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
            playTogether(scaleX, scaleY, iconAlpha, splashAlpha)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    splashScreenViewProvider.remove()
                }
            })
            start()
        }
    }
}
