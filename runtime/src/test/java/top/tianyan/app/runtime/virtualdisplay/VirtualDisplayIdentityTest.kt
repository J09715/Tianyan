package top.tianyan.app.runtime.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VirtualDisplayIdentityTest {
    private val dump = """
        Display 4619827551948147201 (HWC display 0): port=0 displayName="Built-in Screen"
        Display 11529215046613627067 (HWC display 1): displayName="ShowerVirtualDisplay-111"
        Display 11529215046613627068 (HWC display 2): displayName="ShowerVirtualDisplay-222"
    """.trimIndent()
    @Test fun selectsExactSessionAndKeepsUnsignedId() {
        assertEquals("11529215046613627068", physicalDisplayIdForName(dump, "ShowerVirtualDisplay-222"))
        assertEquals("11529215046613627067", physicalDisplayIdForName(dump, "ShowerVirtualDisplay-111"))
    }
    @Test fun refusesMainScreenMissingNamesAndAmbiguousMatches() {
        assertNull(physicalDisplayIdForName(dump, "Built-in Screen"))
        assertNull(physicalDisplayIdForName(dump, "ShowerVirtualDisplay-11"))
        assertNull(physicalDisplayIdForName(dump, "ShowerVirtualDisplay-333"))
        assertNull(physicalDisplayIdForName(dump + "\n" + dump, "ShowerVirtualDisplay-111"))
    }
    @Test fun resolvesPrivateLogicalDisplaysWithoutUsingListOrder() {
        val logical = """
            mBaseDisplayInfo=DisplayInfo{"ShowerVirtualDisplay-111", displayId 2, real 1080 x 2400}
            mOverrideDisplayInfo=DisplayInfo{"ShowerVirtualDisplay-111", displayId 2, real 1080 x 2400}
            mBaseDisplayInfo=DisplayInfo{"ShowerVirtualDisplay-222", displayId 3, real 1080 x 2400}
        """.trimIndent()
        assertEquals("ShowerVirtualDisplay-111", logicalDisplayName(logical, 2))
        assertEquals("ShowerVirtualDisplay-222", logicalDisplayName(logical, 3))
        assertNull(logicalDisplayName(logical, 4))
        assertNull(logicalDisplayName(logical + "\n" + """DisplayInfo{"Another", displayId 2, real 1 x 1}""", 2))
    }
}
