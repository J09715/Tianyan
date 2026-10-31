package top.tianyan.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWindowSizeTest {
    @Test fun phoneWidthsStayCompact() {
        assertEquals(RuntimeWidthClass.Compact, widthClassOf(360f))
        assertEquals(RuntimeWidthClass.Compact, widthClassOf(599.9f))
    }

    @Test fun mediumStartsAtSixHundredDp() {
        assertEquals(RuntimeWidthClass.Medium, widthClassOf(600f))
        assertEquals(RuntimeWidthClass.Medium, widthClassOf(839.9f))
    }

    @Test fun expandedStartsAtEightHundredFortyDp() {
        assertEquals(RuntimeWidthClass.Expanded, widthClassOf(840f))
        assertEquals(RuntimeWidthClass.Expanded, widthClassOf(1280f))
    }

    @Test fun onlyCompactUsesTheOverlayDrawer() {
        assertFalse(RuntimeWidthClass.Compact.hasPersistentNavigation)
        assertTrue(RuntimeWidthClass.Medium.hasPersistentNavigation)
        assertTrue(RuntimeWidthClass.Expanded.hasPersistentNavigation)
    }
}
