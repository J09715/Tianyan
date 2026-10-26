package top.tianyan.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import top.tianyan.app.ui.components.FoldablePreviews
import top.tianyan.app.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun OnboardingScreenPreview() {
    RuntimePreview {
        OnboardingScreen()
    }
}

@FoldablePreviews
@Composable
private fun OnboardingSystemPagePreview() {
    RuntimePreview {
        OnboardingScreen(previewPage = 0)
    }
}

@Preview(name = "欢迎 · 深色", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "zh")
@Preview(name = "欢迎 · 大字体", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "zh")
@Preview(name = "Welcome · English", widthDp = 360, heightDp = 800, locale = "en")
@Composable
private fun OnboardingWelcomeVariantsPreview() {
    RuntimePreview { OnboardingScreen() }
}

@FoldablePreviews
@Preview(name = "模型接入 · 窄屏大字体", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "模型接入 · 深色", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "zh")
@Preview(name = "Model setup · English", widthDp = 360, heightDp = 800, locale = "en")
@Composable
private fun OnboardingModelPagePreview() {
    RuntimePreview {
        OnboardingScreen(previewPage = 2)
    }
}

@FoldablePreviews
@Preview(name = "权限引导 · 窄屏大字体", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "权限引导 · 深色", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "zh")
@Preview(name = "Permissions · English", widthDp = 360, heightDp = 800, locale = "en")
@Composable
private fun OnboardingPermissionPagePreview() {
    RuntimePreview { OnboardingScreen(previewPage = 1) }
}

@Preview(name = "免责声明 · 窄屏大字体", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "Disclaimer · Dark", widthDp = 360, heightDp = 800,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun OnboardingDisclaimerPreview() {
    RuntimePreview { OnboardingScreen(previewPage = -2) }
}
