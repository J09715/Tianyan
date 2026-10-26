package top.tianyan.app.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBundleScriptsTest {
    private val allIds = BuiltinPluginBundles.bundles.flatMap { it.components }.map { it.id }.toSet()

    @Test
    fun freshInstallDoesNotDeleteToolchainsOrReinstallPackages() {
        val script = BuiltinPluginBundles.buildBatchInstallScript(setOf("flutter")).joinToString("\n")
        assertTrue(script.contains("/bin/sh /opt/tianyan/scripts/setup_flutter.sh"))
        assertTrue(script.contains("apt-get"))
        assertFalse(script.contains("--reinstall"))
        assertFalse(script.contains("rm -rf -- /opt/flutter"))
        assertFalse(script.contains("rm -rf -- /opt/android-sdk"))
        assertTrue(script.contains("python3"))
        assertFalse(script.contains(" ripgrep"))
    }

    @Test
    fun reinstallWipesOnlyTheSelectedComponentBeforeSetup() {
        val script = BuiltinPluginBundles.buildBatchInstallScript(setOf("flutter"), reinstall = true).joinToString("\n")
        val purgeAt = script.indexOf("rm -rf -- /opt/flutter")
        val setupAt = script.indexOf("/bin/sh /opt/tianyan/scripts/setup_flutter.sh")
        assertTrue(purgeAt >= 0)
        assertTrue(setupAt > purgeAt)
        assertFalse(script.contains("/opt/android-sdk"))
        assertFalse(script.contains("rm -rf -- /opt/tianyan/bin"))
    }

    @Test
    fun uninstallFlutterKeepsAndroidCoreAndBasePackages() {
        val script = BuiltinPluginBundles.buildBatchUninstallScript(
            selectedComponentIds = setOf("flutter"),
            retainedComponentIds = setOf("android-core"),
        ).joinToString("\n")
        assertTrue(script.contains("rm -rf -- /opt/flutter"))
        assertTrue(script.contains("/opt/tianyan/bin/flutter"))
        assertFalse(script.contains("/opt/android-sdk"))
        assertFalse(script.contains("apt-get remove"))
        assertFalse(script.contains("openjdk"))
    }

    @Test
    fun uninstallAndroidCoreKeepsPackagesStillUsedByReverseTools() {
        val script = BuiltinPluginBundles.buildBatchUninstallScript(
            selectedComponentIds = setOf("android-core"),
            retainedComponentIds = setOf("android-re", "android-ndk"),
        ).joinToString("\n")
        assertTrue(script.contains("rm -rf -- /opt/android-sdk"))
        assertFalse(script.contains("/opt/tianyan/toolchains/android/ndk"))
        assertFalse(script.contains("openjdk-17-jdk-headless"))
        assertTrue(script.contains("apt-get remove"))
        assertTrue(script.contains("adb"))
    }

    @Test
    fun uninstallAndroidCoreRemovesJdkWhenNothingElseNeedsIt() {
        val script = BuiltinPluginBundles.buildBatchUninstallScript(
            selectedComponentIds = setOf("android-core"),
            retainedComponentIds = emptySet(),
        ).joinToString("\n")
        assertTrue(script.contains("/opt/tianyan/toolchains/android/ndk"))
        assertTrue(script.contains("openjdk-17-jdk-headless"))
        assertTrue(script.contains("rm -f -- /usr/local/bin/java /usr/bin/java"))
    }

    @Test
    fun uninstallCodeSearchRemovesItsPackagesButNotBaseTools() {
        val script = BuiltinPluginBundles.buildBatchUninstallScript(
            selectedComponentIds = setOf("code-search-toolkit"),
            retainedComponentIds = emptySet(),
        ).joinToString("\n")
        assertTrue(script.contains("ripgrep"))
        assertTrue(script.contains("fd-find"))
        assertFalse(script.contains(" python3"))
        assertFalse(script.contains(" curl"))
        assertTrue(script.contains("/opt/tianyan/bin/rg"))
    }

    @Test
    fun nodePackageManagerUninstallRunsBeforeRemovingNode() {
        val steps = BuiltinPluginBundles.buildBatchUninstallScript(
            selectedComponentIds = setOf("nodejs-pkg"),
            retainedComponentIds = setOf("nodejs-core"),
        )
        val joined = steps.joinToString("\n")
        assertTrue(joined.contains("npm uninstall -g pnpm yarn"))
        assertFalse(joined.contains("apt-get remove"))
        assertFalse(joined.contains(" nodejs"))
    }

    @Test
    fun flutterBlocksUninstallOfAndroidCoreUntilItIsRemovedToo() {
        val blockers = BuiltinPluginBundles.blockingDependents(
            componentId = "android-core",
            installedIds = setOf("android-core", "flutter"),
        )
        assertTrue(blockers.map { it.id }.contains("flutter"))
        val together = BuiltinPluginBundles.blockingDependents(
            componentId = "android-core",
            installedIds = setOf("android-core", "flutter"),
            alsoRemoving = setOf("android-core", "flutter"),
        )
        assertTrue(together.isEmpty())
    }

    @Test
    fun everyBuiltinPurgeTargetStaysInsideTheAllowList() {
        allIds.forEach { id ->
            val retained = allIds - id
            val uninstall = BuiltinPluginBundles.buildBatchUninstallScript(setOf(id), retained)
            val reinstall = BuiltinPluginBundles.buildBatchInstallScript(setOf(id), reinstall = true)
            (uninstall + reinstall).forEach { step ->
                assertFalse(step, step.contains("rm -rf -- /opt/tianyan ") || step.contains("rm -rf -- / "))
                assertFalse(step, step.contains("rm -rf -- /usr"))
                assertFalse(step, step.contains("rm -rf -- /opt "))
            }
        }
    }
}
