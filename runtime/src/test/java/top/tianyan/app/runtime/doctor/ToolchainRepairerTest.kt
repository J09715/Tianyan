package top.tianyan.app.runtime.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainRepairerTest {

    @Test
    fun aptScriptUpdatesOnceAndFallsBackToFixBrokenInstall() {
        val opts = "-o Acquire::Retries=2 -o Acquire::ForceIPv4=true"
        val lines = ToolchainRepairer.aptCommandLines(opts, listOf("strace", "patchelf"))
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("apt-get $opts update -y || true"))
        assertEquals(1, Regex("update -y").findAll(joined).count())
        assertTrue(joined.contains("-f install -y --no-install-recommends"))
        assertTrue(joined.contains("install -y --no-install-recommends strace || {"))
        assertTrue(joined.contains("install -y --no-install-recommends patchelf || {"))
        assertTrue(joined.contains("apt-get $opts install -y --no-install-recommends strace; }"))
        assertFalse(joined.contains("chmod"))
        assertFalse(joined.contains("sources.list"))
        assertFalse(joined.contains("sources.list.d"))
    }
}
