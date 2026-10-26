package top.tianyan.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingIntroStepTest {
    @Test fun restoredLinuxDoesNotSkipWelcomeOrDisclaimer() {
        val restoredSetupPage = 1
        assertEquals(0, OnboardingIntroStep.WELCOME.stage(restoredSetupPage))
        assertEquals(1, OnboardingIntroStep.WELCOME.advance(false).stage(restoredSetupPage))
    }

    @Test fun uncheckedNoticeCannotAdvanceEvenWhenRuntimeIsReady() {
        val pending = OnboardingIntroStep.DISCLAIMER.advance(acknowledged = false)
        assertEquals(1, pending.stage(setupPage = 1))
    }

    @Test fun confirmationPreservesExistingRuntimeAndModelProgress() {
        val confirmed = OnboardingIntroStep.DISCLAIMER.advance(acknowledged = true)
        assertEquals(3, confirmed.stage(setupPage = 1))
        assertEquals(4, confirmed.stage(setupPage = 2))
    }

    @Test fun returningFromSetupReopensNoticeAndRetainsConfirmationRequirement() {
        val notice = OnboardingIntroStep.SETUP.previous()
        assertEquals(OnboardingIntroStep.DISCLAIMER, notice)
        assertEquals(notice, notice.advance(acknowledged = false))
        assertEquals(OnboardingIntroStep.WELCOME, notice.previous())
    }
}
