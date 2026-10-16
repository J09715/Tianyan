package top.tianyan.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSectionSelectionTest {

    @Test
    fun `default selection is all selected`() {
        val selection = BackupSectionSelection()
        assertTrue(selection.allSelected())
        assertFalse(selection.isEmpty())
        assertTrue(selection.selected(BackupManager.SECTION_DB))
        assertTrue(selection.selected(BackupManager.SECTION_PREFS))
        assertTrue(selection.selected(BackupManager.SECTION_BLOBS))
        assertTrue(selection.selected(BackupManager.SECTION_FILES))
        assertTrue(selection.selected(BackupManager.SECTION_CHECKPOINTS))
    }

    @Test
    fun `unchecking one section breaks all-selected`() {
        val selection = BackupSectionSelection(db = false)
        assertFalse(selection.allSelected())
        assertFalse(selection.isEmpty())
        assertFalse(selection.selected(BackupManager.SECTION_DB))
        assertTrue(selection.selected(BackupManager.SECTION_PREFS))
    }

    @Test
    fun `unchecking everything yields empty selection`() {
        val selection = BackupSectionSelection(
            db = false, prefs = false, blobs = false, files = false, checkpoints = false,
        )
        assertTrue(selection.isEmpty())
        assertFalse(selection.allSelected())
    }

    @Test
    fun `with toggles a single section without touching others`() {
        val selection = BackupSectionSelection().with(BackupManager.SECTION_FILES, false)
        assertFalse(selection.selected(BackupManager.SECTION_FILES))
        assertTrue(selection.selected(BackupManager.SECTION_DB))
        assertTrue(selection.selected(BackupManager.SECTION_CHECKPOINTS))
    }

    @Test
    fun `unknown section is neither selected nor toggled`() {
        val selection = BackupSectionSelection()
        assertFalse(selection.selected("unknown"))
        assertTrue(selection.with("unknown", true) === selection || selection.with("unknown", true).allSelected())
    }
}
