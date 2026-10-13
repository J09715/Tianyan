package top.tianyan.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.EnvironmentVariable

class LinuxEnvironmentProfileTest {
    @Test
    fun parsesCurrentEnvironmentSnapshotBetweenMarkers() {
        val output = """
            profile noise
            __TIANYAN_ENV_SNAPSHOT_BEGIN__
            HOME=/root
            EMPTY=
            VALUE=one=two
            invalid line
            __TIANYAN_ENV_SNAPSHOT_END__
            trailing noise
        """.trimIndent()

        assertEquals(
            listOf(
                EffectiveEnvironmentVariable("EMPTY", ""),
                EffectiveEnvironmentVariable("HOME", "/root"),
                EffectiveEnvironmentVariable("VALUE", "one=two"),
            ),
            LinuxEnvironmentSnapshot.parse(output),
        )
    }

    @Test
    fun renderAndParseRoundTripShellSensitiveValues() {
        val records = listOf(
            LinuxEnvironmentRecord(
                metadata = EnvironmentVariable(
                    id = "first-id",
                    key = "API_TOKEN",
                    note = "包含空格与中文",
                    createdAt = 123L,
                ),
                value = "a value with 'quotes' and spaces",
            ),
            LinuxEnvironmentRecord(
                metadata = EnvironmentVariable(
                    id = "second-id",
                    key = "EMPTY_NOTE",
                    createdAt = 456L,
                ),
                value = "dollar=\$PATH; command=\$(false)",
            ),
        )

        val rendered = LinuxEnvironmentProfile.render(records)

        assertEquals(records, LinuxEnvironmentProfile.parse(rendered))
        assertTrue(rendered.contains("export API_TOKEN='a value with '\\''quotes'\\'' and spaces'"))
        assertTrue(rendered.contains("export EMPTY_NOTE='dollar=\$PATH; command=\$(false)'"))
    }

    @Test
    fun parseIgnoresOrdinaryProfileLinesAndMalformedMetadata() {
        val content = """
            export PATH=/usr/bin
            # TIANYAN_ENV_V1|broken
            # a regular comment
        """.trimIndent()

        assertTrue(LinuxEnvironmentProfile.parse(content).isEmpty())
    }
}
