package top.tianyan.app.ui.onboarding

/** Runtime restoration can advance setup without skipping the first-run usage notice. */
internal enum class OnboardingIntroStep {
    WELCOME, DISCLAIMER, SETUP;

    fun advance(acknowledged: Boolean): OnboardingIntroStep = when (this) {
        WELCOME -> DISCLAIMER
        DISCLAIMER -> if (acknowledged) SETUP else DISCLAIMER
        SETUP -> SETUP
    }

    fun previous(): OnboardingIntroStep = when (this) {
        WELCOME, DISCLAIMER -> WELCOME
        SETUP -> DISCLAIMER
    }

    fun stage(setupPage: Int): Int = when (this) {
        WELCOME -> 0
        DISCLAIMER -> 1
        SETUP -> when {
            setupPage <= 0 -> 2
            setupPage == 1 -> 3
            else -> 4
        }
    }
}
