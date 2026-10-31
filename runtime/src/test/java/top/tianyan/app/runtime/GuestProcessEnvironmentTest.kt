package top.tianyan.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestProcessEnvironmentTest {
    private val linuxPath = EnvironmentResolver().runtimePath()
    private val androidPath =
        "/product/bin:/apex/com.android.runtime/bin:/system_ext/bin:/system/bin:/system/xbin:/vendor/bin"

    @Test
    fun prootLaunchReplacesAndroidPathWithLinuxPath() {
        val env = GuestProcessEnvironment.interactiveLaunchEnvironment(
            androidEnvironment = mapOf(
                "PATH" to androidPath,
                "HOME" to "/data/user/0/top.tianyan.app",
                "ANDROID_DATA" to "/data",
            ),
            hostEnvironment = mapOf("PROOT_LOADER" to "/data/app/libtalloc.so", "LD_LIBRARY_PATH" to "/data/app/bin"),
            executable = "/data/app/bin/proot",
            fallbackHome = "/",
        )
        assertEquals(linuxPath, env["PATH"])
        assertFalse(env["PATH"]!!.contains("/system/bin"))
        assertFalse(env["PATH"]!!.contains("/vendor/bin"))
        assertFalse(env["PATH"]!!.contains("/apex/"))
        assertEquals("/data/app/libtalloc.so", env["PROOT_LOADER"])
        assertEquals("/data/app/bin", env["LD_LIBRARY_PATH"])
        assertEquals("/data/user/0/top.tianyan.app", env["HOME"])
        assertTrue(env["PATH"]!!.startsWith("/root/.local/bin:/opt/tianyan/bin:"))
        assertTrue(env["PATH"]!!.contains(":/usr/bin:"))
    }

    @Test
    fun suLaunchKeepsAndroidPathSoTheHostCanResolveSu() {
        val env = GuestProcessEnvironment.commandLaunchEnvironment(
            hostEnvironment = mapOf("PROOT_TMP_DIR" to "/tmp"),
            executable = "su",
            androidPath = androidPath,
        )
        assertEquals(androidPath, env["PATH"])
        assertEquals("/tmp", env["PROOT_TMP_DIR"])
        assertEquals(GuestProcessEnvironment.ANDROID_HOST_PATH, GuestProcessEnvironment.pathForLaunch("su", null))
        assertEquals(GuestProcessEnvironment.ANDROID_HOST_PATH, GuestProcessEnvironment.pathForLaunch("/system/xbin/su", " "))
    }

    @Test
    fun commandLaunchWithoutAnInheritedPathUsesTheLinuxPath() {
        val env = GuestProcessEnvironment.commandLaunchEnvironment(
            hostEnvironment = mapOf("TMPDIR" to "/data/local/tmp"),
            executable = "/data/app/bin/proot",
        )
        assertEquals(linuxPath, env["PATH"])
        assertEquals("/data/local/tmp", env["TMPDIR"])
    }

    @Test
    fun interactiveSuDoesNotDropOtherAndroidVariables() {
        val env = GuestProcessEnvironment.interactiveLaunchEnvironment(
            androidEnvironment = mapOf("PATH" to androidPath, "ANDROID_ROOT" to "/system"),
            hostEnvironment = emptyMap(),
            executable = "su",
            fallbackHome = "/data/user/0/top.tianyan.app",
        )
        assertEquals(androidPath, env["PATH"])
        assertEquals("/system", env["ANDROID_ROOT"])
        assertEquals("/data/user/0/top.tianyan.app", env["HOME"])
    }
}
