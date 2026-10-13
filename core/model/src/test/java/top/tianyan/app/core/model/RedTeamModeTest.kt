package top.tianyan.app.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RedTeamModeTest {
    @Test
    fun unknownModeAndPhaseFallBackToSafeDefaults() {
        assertEquals(RedTeamMode.OFF, RedTeamMode.fromId("legacy-mode"))
        assertEquals(RedTeamPhase.IDLE, RedTeamPhase.fromId("legacy-phase"))
    }

    @Test
    fun persistedIdsRoundTrip() {
        RedTeamMode.entries.forEach { assertEquals(it, RedTeamMode.fromId(it.id)) }
        RedTeamPhase.entries.forEach { assertEquals(it, RedTeamPhase.fromId(it.id)) }
    }
}
