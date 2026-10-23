package top.tianyan.app.runtime.uml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UmlEngineSelector.resolve 纯逻辑验证：UML 是默认引擎，只有两种情况回退 PRoot——
 * 用户显式关掉（opt-out）或产物未就位（镜像下载中 / 四件套缺失）。
 */
class UmlEngineSelectorTest {

    @Test
    fun umlPreferenceWithAllArtifactsResolvesToUml() {
        assertEquals(EngineType.UML, UmlEngineSelector.resolve(EngineType.UML, emptyList()))
    }

    @Test
    fun umlPreferenceMissingRootfsFallsBackToProot() {
        val resolved = UmlEngineSelector.resolve(
            EngineType.UML,
            listOf(UmlRuntimeLayout.UML_ROOTFS_NAME),
        )
        assertEquals(EngineType.PROOT, resolved)
    }

    @Test
    fun prootPreferenceAlwaysStaysProot() {
        assertEquals(
            EngineType.PROOT,
            UmlEngineSelector.resolve(EngineType.PROOT, emptyList()),
        )
    }

    @Test
    fun missingAnyArtifactBlocksUml() {
        val missing = listOf(UmlRuntimeLayout.UML_KERNEL_NAME, UmlRuntimeLayout.UML_PASST_NAME)
        assertEquals(EngineType.PROOT, UmlEngineSelector.resolve(EngineType.UML, missing))
    }
}
