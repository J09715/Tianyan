package top.tianyan.app.runtime.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.SandboxToolchainCatalog
import top.tianyan.app.core.model.ToolchainStatus

class ToolchainProbeParserTest {

    @Test
    fun parsesReadyToolAndMarksTruncatedTailUnknown() {
        val probes = SandboxToolchainCatalog.probes
        val stdout = """
            __TX__cmake__PATH__/usr/bin/cmake
            __TX__cmake__VER__cmake version 3.28.3
        """.trimIndent()

        val parsed = ToolchainProbeParser.parse(stdout, probes)

        assertEquals(ToolchainStatus.READY, parsed.getValue("cmake").status)
        assertEquals("3.28.3", parsed.getValue("cmake").version)
        assertEquals("/usr/bin/cmake", parsed.getValue("cmake").resolvedPath)
        probes.filter { it.id != "cmake" }.forEach { probe ->
            assertEquals(probe.id, ToolchainStatus.UNKNOWN, parsed.getValue(probe.id).status)
        }
    }

    @Test
    fun pathWithoutVersionMarkerIsUnknown() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "apksigner" }
        val parsed = ToolchainProbeParser.parse(
            "__TX__apksigner__PATH__/usr/bin/apksigner\n",
            listOf(probe),
        )
        assertEquals(ToolchainStatus.UNKNOWN, parsed.getValue("apksigner").status)
        assertTrue(parsed.getValue("apksigner").resolvedPath == null)
    }

    @Test
    fun emptyVersionMarkerWithNoMinimumStaysReady() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "apksigner" }
        val parsed = ToolchainProbeParser.parse(
            "__TX__apksigner__PATH__/usr/bin/apksigner\n__TX__apksigner__VER__\n",
            listOf(probe),
        )
        assertEquals(ToolchainStatus.READY, parsed.getValue("apksigner").status)
    }
}
